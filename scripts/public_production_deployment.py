#!/usr/bin/env python3
"""Owner-triggered production deployment rehearsal. Real-host operation is blocked."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import sys
import time
import uuid

import private_dev_promotion as trust

ROOT = Path(__file__).resolve().parents[1]
require = trust.require


def runtime_path(path):
    return (path.startswith('deploy/public-production/') and not path.endswith('.md')
            or path.startswith('docker/postgres/init/')
            or path in ('deploy/private-dev/bin/run-application', 'deploy/private-dev/bin/run-migrations',
                        'tools/catalogue-localization/models.json'))


def inventory(tree, predicate):
    require(not tree.get('truncated', True) and len(tree['tree']) <= 10000,
            'Source inventory unavailable or too large.')
    entries = []
    for item in tree['tree']:
        if item['type'] == 'tree' or not predicate(item['path']):
            continue
        require(item['type'] == 'blob' and item['mode'] in ('100644', '100755'),
                'Runtime sources must be regular files.')
        entries.append(f"{item['mode']} blob {item['sha']}\t{item['path']}".encode())
    require(entries, 'Required source inventory is empty.')
    return trust.contract_hash(entries)


def local_configuration():
    # Actual bytes/modes, including newly added files; index flags cannot hide drift.
    paths = set()
    for directory in ('deploy/public-production', 'docker/postgres/init'):
        paths.update(p for p in (ROOT / directory).rglob('*') if not p.is_dir())
    paths.update(ROOT / p for p in ('deploy/private-dev/bin/run-application',
                                   'deploy/private-dev/bin/run-migrations', 'tools/catalogue-localization/models.json'))
    tree = {'truncated': False, 'tree': []}
    for path in sorted(paths):
        relative = path.relative_to(ROOT).as_posix()
        if not runtime_path(relative):
            continue
        require(path.is_file() and not path.is_symlink(), 'Unsafe runtime source.')
        data = path.read_bytes()
        blob = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
        tree['tree'].append({'path': relative, 'type': 'blob', 'sha': blob,
                             'mode': '100755' if path.stat().st_mode & 0o111 else '100644'})
    return inventory(tree, runtime_path)


def verify_configuration(api, revision, expected):
    require(re.fullmatch(r'[0-9a-f]{64}', expected or ''), 'An approved configuration fingerprint is required.')
    tree = api.request(f'/git/trees/{revision}?recursive=1')
    require(local_configuration() == expected == inventory(tree, runtime_path),
            'Configuration fingerprint differs from the approved input or trusted image source; separate runtime rollout required.')
    schema = inventory(tree, lambda p: p.startswith('backend/src/main/resources/db/migration/') and p.endswith('.sql'))
    return schema


def verify_selection(api, selected):
    run, _ = trust.verify_trusted_main(api, selected['source_revision'], selected['image_digest'],
                                     selected['source_run_id'], selected['source_run_attempt'])
    trust.verify_publication(api, run, selected['source_revision'], selected['image_digest'])


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w') as output:
            output.write(json.dumps(value, indent=2) + '\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


class Deployment:
    """One serialized migration/activation attempt within an isolated Exercise.

    The only executable adapter is the existing disposable runtime. No host flag,
    arbitrary Compose file, credentials directory or approval override is accepted.
    """
    def __init__(self, exercise, api, fingerprint, schema):
        self.runtime, self.api = exercise, api
        self.fingerprint, self.schema = fingerprint, schema
        self.state = exercise.directory / 'deployment-state.json'
        self.lock = exercise.directory / 'deployment.lock'
        self.check = 'state'

    def history(self):
        # Aggregate only Flyway metadata in PostgreSQL; never transfer catalogue/user rows.
        return self.runtime.sql("SELECT CASE WHEN bool_and(success) THEN md5(string_agg(version || ':' || script || ':' || checksum::text, ',' ORDER BY installed_rank)) END FROM flyway_schema_history;")

    def migration_version(self):
        value = self.runtime.sql('SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;')
        require(re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', value or ''), 'Invalid migration version.')
        return value

    def preflight(self, previous):
        r = self.runtime
        self.check = 'synthetic-isolation'
        require(re.fullmatch(r'vgp-production-test-[0-9a-f]{12}', r.project), 'Only isolated synthetic rehearsal is enabled; real-host release/recovery gates remain open.')
        config = json.loads(r.file.read_text())
        require(hashlib.sha256(r.file.read_bytes()).hexdigest() == r.receipt['configurationSha256'],
                'Rendered configuration changed after validation.')
        require(all(n.get('internal') is True and n['name'].startswith(r.project + '-') for n in config['networks'].values())
                and all(v['name'].startswith(r.project + '-') for v in config['volumes'].values())
                and all(not s.get('ports') and 'build' not in s for s in config['services'].values()),
                'Rehearsal isolation contract violated.')
        require(config['services']['application']['image'] == config['services']['migration']['image'] == r.selected['images']['application'],
                'Migration and target release must use the same immutable image.')
        self.check = 'provenance'
        trust.validate_inputs(r.selected['source_revision'], r.selected['image_digest'],
                              r.selected['source_run_id'], r.selected['source_run_attempt'])
        require(r.selected['images']['application'] == trust.IMAGE_REPOSITORY + '@' + r.selected['image_digest'],
                'Selected image does not match the trusted digest.')
        verify_selection(self.api, r.selected)
        image = json.loads(r.inspect_image(r.selected['images']['application']).stdout)[0]
        labels = image['Config'].get('Labels') or {}
        require(image['Os'] == 'linux' and image['Architecture'] == 'amd64'
                and labels.get('org.opencontainers.image.revision') == r.selected['source_revision']
                and labels.get('org.opencontainers.image.version') == r.selected['applicationVersion'],
                'Candidate image platform or source/version labels differ from the release.')
        self.check = 'configuration'
        require(verify_configuration(self.api, r.selected['source_revision'], self.fingerprint) == self.schema,
                'Migration inventory changed.')
        self.check = 'secret-files'
        secret_directory = r.directory / 'secrets'
        require(not secret_directory.is_symlink() and secret_directory.stat().st_mode & 0o777 == 0o750,
                'Unsafe synthetic secret directory.')
        for secret in config['secrets'].values():
            path = Path(secret['file'])
            require(path.parent == r.directory / 'secrets' and path.is_file() and not path.is_symlink()
                    and path.stat().st_mode & 0o777 == 0o640 and path.stat().st_gid == os.getgid(),
                    'Missing or unsafe synthetic secret file.')
            require(path.name.startswith('igdb-') or path.stat().st_size > 0, 'Required synthetic secret is empty.')
        self.check = 'dependencies'
        for service in ('postgres', 'keycloak', 'caddy'):
            cid = r.cid(service)
            require(bool(cid) and len(cid.split()) == 1, 'Required dependency is missing or ambiguous.')
            info = json.loads(r.inspect(cid).stdout)[0]
            require(info['Config']['Image'] == config['services'][service]['image'] and info['State']['Running']
                    and info['State'].get('Health', {}).get('Status', 'healthy') == 'healthy',
                    'Dependency image/state differs from the approved runtime.')
        self.check = 'installed-state'
        cid = r.cid('application')
        if cid:
            require(previous is not None and previous.get('outcome') != 'running',
                    'Unrecorded or interrupted application state; assess recovery before retry.')
            require(len(cid.split()) == 1, 'Ambiguous application state.')
            info = json.loads(r.inspect(cid).stdout)[0]
            require(info['Config']['Image'] == previous.get('installedImage', previous['image']), 'Application differs from the recorded deployment.')
        if previous:
            require(previous.get('outcome') in ('success', 'failure') and previous.get('configurationFingerprint') == self.fingerprint,
                    'Incomplete deployment state or separate runtime rollout required.')

    def smoke(self):
        # Anonymous, bounded reads; no account, session credentials, writes or provider calls.
        info = json.loads(self.runtime.compose('exec', '-T', 'application', 'wget', '-qO-', '-T', '15',
                                              'http://127.0.0.1:8081/actuator/info').stdout)
        require(info.get('build', {}).get('version') == self.runtime.selected['applicationVersion']
                and info.get('build', {}).get('sourceRevision') == self.runtime.selected['source_revision'],
                'Running application source/version metadata differs from the release.')
        self.runtime.probe('python3', '-', data='''import http.client,json,ssl
ctx=ssl.create_default_context(cafile='/run/pki/ca.crt')
def get(path):
 c=http.client.HTTPSConnection('gameometro.com',context=ctx,timeout=15)
 c.request('GET',path); r=c.getresponse(); b=r.read(262145); c.close()
 assert len(b)<=262144
 return r.status,b
status,body=get('/'); assert status==200 and b'<html' in body.lower()
status,body=get('/api/v1/releases?page=1&pageSize=1')
v=json.loads(body)
assert status==200 or status==503 and v.get('code')=='CATALOGUE_NOT_READY'
assert get('/actuator/health')[0]==404
''')

    def run(self, *, rollback=False, fault=None):
        require(fault in (None, 'migration', 'readiness', 'smoke'), 'Unknown rehearsal fault.')
        r = self.runtime
        receipt = {'startedAtUnix': int(time.time()), 'outcome': 'failure', 'phase': 'lock', 'failureReason': None,
                   'sourceRevision': r.selected['source_revision'], 'image': r.selected['images']['application'],
                   'applicationVersion': r.selected['applicationVersion'], 'configurationFingerprint': self.fingerprint,
                   'renderedConfigurationSha256': r.receipt['configurationSha256'], 'schemaInventory': self.schema,
                   'migrationVersion': None, 'historyFingerprint': None, 'previousVersion': None,
                   'previousImage': None, 'productionReady': False, 'mode': 'isolated-synthetic-rehearsal',
                   'injectedFault': fault, 'rollbackRequested': rollback,
                   'sourceRunId': r.selected['source_run_id'], 'sourceRunAttempt': r.selected['source_run_attempt'],
                   'securityRunId': r.selected['securityRunId']}
        path = r.evidence / ('deployment-' + uuid.uuid4().hex + '.json')
        locked = False
        mutated = False
        lock = None
        try:
            lock = self.lock.open('a')
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                locked = True
            except BlockingIOError:
                raise trust.Refused('Concurrent deployment refused.') from None
            receipt['phase'] = 'preflight'
            self.check = 'state'
            previous = json.loads(self.state.read_text()) if self.state.exists() else None
            if previous:
                require(previous.get('outcome') in ('success', 'failure')
                        and re.fullmatch(re.escape(trust.IMAGE_REPOSITORY) + r'@sha256:[0-9a-f]{64}', previous.get('image', ''))
                        and re.fullmatch(r'[0-9a-f]{40}', previous.get('sourceRevision', ''))
                        and re.fullmatch(r'[0-9A-Za-z.-]{1,100}', previous.get('applicationVersion', '')),
                        'Incomplete or interrupted deployment state; assess recovery before retry.')
                receipt['previousVersion'], receipt['previousImage'] = previous.get('installedVersion', previous['applicationVersion']), previous.get('installedImage', previous['image'])
            receipt['installedImage'] = previous.get('installedImage', previous['image']) if previous else None
            receipt['installedVersion'] = previous.get('installedVersion', previous['applicationVersion']) if previous else None
            self.preflight(previous)
            self.check = 'release-order-and-rollback'
            if previous and previous['sourceRevision'] != receipt['sourceRevision']:
                comparison = self.api.request(f"/compare/{previous['sourceRevision']}...{receipt['sourceRevision']}")
                require(comparison.get('status') in ('ahead', 'behind'), 'Unrelated release history refused.')
                backwards = comparison['status'] == 'behind'
                require(not backwards or rollback, 'Older release requires explicit schema-compatible rollback.')
                require(not rollback or backwards, 'Rollback must select an older trusted release.')
            elif rollback:
                raise trust.Refused('Rollback must select an older trusted release.')
            if rollback:
                require(previous['outcome'] == 'success' and previous['schemaInventory'] == self.schema
                        and previous['historyFingerprint'] == self.history(),
                        'Rollback compatibility is not demonstrated; forward fix or separately approved recovery required.')
            # A first release may have no Flyway history table yet. The actor owns
            # its creation; only inspect a previously recorded migration baseline.
            if previous and previous.get('migrationVersion'):
                receipt['migrationVersion'] = self.migration_version()
                receipt['historyFingerprint'] = self.history()
            receipt['phase'] = 'migration'
            atomic_json(self.state, {**receipt, 'outcome': 'running'})
            mutated = True
            # Stop old code before DDL; even a partially failed migration cannot leave
            # it serving against an unassessed schema. No volume/database reset occurs.
            r.stop_application()
            args = ['run', '--rm', '--no-deps']
            if fault == 'migration':
                args += ['-e', 'APPLICATION_MIGRATION_DB_URL=jdbc:postgresql://postgres:5432/rehearsal_missing_database']
            result = r.compose(*args, 'migration', check=False)
            require(result.returncode == 0, 'Migration failed; activation prevented. Forward fix or assessed recovery required.')
            receipt['migrationVersion'] = self.migration_version()
            receipt['historyFingerprint'] = self.history()
            require(re.fullmatch(r'[0-9a-f]{32}', receipt['historyFingerprint'] or ''), 'Invalid Flyway history.')
            if rollback:
                require(receipt['historyFingerprint'] == previous['historyFingerprint'], 'Rollback changed schema history; activation prevented.')
            receipt['phase'] = 'readiness'
            receipt['installedImage'], receipt['installedVersion'] = receipt['image'], receipt['applicationVersion']
            r.up('application', no_deps=True)
            if fault == 'readiness':
                # A real stopped candidate, followed by its actual Docker health observation.
                r.stop_application()
            info = json.loads(r.inspect(r.cid('application')).stdout)[0]
            require(info['State']['Running'] and info['State'].get('Health', {}).get('Status') == 'healthy', 'Candidate readiness failed.')
            receipt['phase'] = 'smoke'
            if fault == 'smoke':
                raise trust.Refused('Injected synthetic smoke failure.')
            self.smoke()
            receipt.update(outcome='success', phase='complete')
        except BaseException:
            # Never export exception strings, command output, credentials or server bodies.
            receipt['failureReason'] = {
                'lock': 'concurrent-or-lock-unavailable', 'preflight': 'preflight-refused',
                'migration': 'migration-failed-activation-prevented', 'readiness': 'readiness-failed',
                'smoke': 'smoke-failed',
            }.get(receipt['phase'], 'deployment-interrupted')
            if receipt['phase'] == 'preflight':
                receipt['failedPreflightCheck'] = self.check
            if mutated:
                try:
                    r.stop_application()
                except BaseException:
                    receipt['stopResult'] = 'failed-private-operator-action-required'
            raise
        finally:
            try:
                receipt['finishedAtUnix'] = int(time.time())
                if mutated:
                    atomic_json(self.state, receipt)
                atomic_json(path, receipt)
            finally:
                if lock:
                    if locked:
                        fcntl.flock(lock, fcntl.LOCK_UN)
                    lock.close()
        return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--print-configuration-fingerprint', action='store_true')
    parser.add_argument('--rehearsal', action='store_true', help='Fresh isolated runtime; synthetic state retained only between attempts')
    parser.add_argument('--revision')
    parser.add_argument('--digest', help='Explicit immutable application sha256 digest; matched against CI publication')
    parser.add_argument('--configuration-fingerprint')
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--model-dir', type=Path)
    args = parser.parse_args()
    if args.print_configuration_fingerprint:
        print(local_configuration())
        return 0
    require(args.rehearsal, 'Real-host deployment blocked: backup/recovery, release and host prerequisites remain unsatisfied. Only --rehearsal is implemented.')
    require(all((args.revision, args.digest, args.configuration_fingerprint, args.evidence)),
            'Revision, immutable digest, approved configuration fingerprint and new evidence directory are required.')
    trust.validate_inputs(args.revision, args.digest, '1', '1')
    import public_production_integration as integration
    return integration.main(['--revision', args.revision, '--digest', args.digest,
                             '--configuration-fingerprint', args.configuration_fingerprint,
                             '--evidence', str(args.evidence), '--stage', 'E',
                             *(['--model-dir', str(args.model_dir)] if args.model_dir else [])])


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        sys.exit(main())
    except (trust.Refused, OSError, ValueError, KeyError) as error:
        print(str(error) if isinstance(error, trust.Refused) else 'Deployment refused; input/state could not be verified. Raw output withheld.', file=sys.stderr)
        sys.exit(1)
