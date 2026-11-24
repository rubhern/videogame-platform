#!/usr/bin/env python3
"""Disposable real-service rehearsal of the production rollback (#488); never a deployment or VPS qualification.

It installs the signed candidate in an isolated Docker project with synthetic accounts, ratings and
keys, as scripts/rehearse-private-production-upgrade.py does. Then it moves the runtime through real
trusted-main publications with the real upgrader, each with a real paired backup and isolated
restore: to --last-known-good, then to --current. That is how production reaches a release, because
every main build republishes Keycloak, the edge and the localizer.

With new user data, a Keycloak session and an edge poller in place, it drives
scripts/production_rollback.py:

1. Refusals: a target other than the recorded predecessor, and a held recovery lock.
2. A handled fault in the smoke phase: the attempt is undone.
3. A real SIGKILL with the candidate serving, followed by --abandon.
4. The authorized rollback, then its verification: data, identity, sessions, Flyway, volumes,
   kept dependencies, lineage and the measured edge availability.
5. A real upgrade from the rolled-back runtime to --incompatible, a release with a migration,
   whose rollback must be FORWARD_FIX_OR_RECOVER.

It needs local Docker, the GitHub CLI logged in (publication evidence) and the three publications
still within their retention. No VPS, production secret or real account is used.
"""
import sys
sys.dont_write_bytecode = True

import argparse
import contextlib
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import tempfile
import time
import traceback
import urllib.parse

import install_private_production as install
import prepare_private_production as prepare
import private_dev_promotion as trust
import private_production_upgrade as upgrade
import production_rollback as rollback
import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('upgrade_rehearsal', ROOT / 'scripts/rehearse-private-production-upgrade.py')
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)
require = trust.require
# Polls the product entry point the BFF serves through the edge, as a visitor would, until stopped; one line
# per request. Four per second stays inside its per-source limit; the search routes allow two.
POLLER = r'''
import http.client,json,ssl,sys,time
ctx=ssl.create_default_context(cafile='/run/private-tls/ca.crt')
end=time.monotonic()+float(sys.argv[1])
while time.monotonic()<end:
 t=time.time()
 try:
  c=http.client.HTTPSConnection('gameometro.com',context=ctx,timeout=5)
  c.request('GET','/');s=c.getresponse().status;c.close()
 except Exception as e:
  s=type(e).__name__
 print(json.dumps([round(t,3),s]),flush=True)
 time.sleep(0.25)
'''


class ChainUpgrade(base.Rehearsal):
    """The upgrade rehearsal, able to jump from the installed candidate over a month of main."""

    def selected(self, api):
        # GitHub's compare over that range exceeds the upgrader's bounded response; production upgrades
        # release by release and keeps the API check. The same strict ancestry is proven locally.
        selected = integration.resolve(api, self.target)
        base.shell(['git', '-C', ROOT, 'merge-base', '--is-ancestor', self.active['revision'], self.target])
        require(self.active['revision'] != self.target, 'The target must be strictly ahead of the active source; no downgrade.')
        return selected


    def create_secret_additions(self, config, added):
        """The reviewed empty secrets of disabled integrations, owned by the unprivileged rehearsal user."""
        created = []
        for name in added:
            path = Path(config['secrets'][name]['file'])
            if path.exists():
                continue
            require(install.optional_secret(path.name, config['services']['application'].get('environment', {})),
                    'An enabled integration needs its secret files provisioned before the upgrade.')
            path.touch(mode=0o640, exist_ok=False)
            created.append(path.name)
        return created


class RollbackRehearsal(rollback.Rollback, base.Rehearsal):
    """The rollback with the upgrade rehearsal's isolated-host seams."""

    def verify_operator(self):
        """The unpublished working tree cannot pass; a real run must, and the report says so."""

    @contextlib.contextmanager
    def cycle_lock(self):
        # No backup timers exist here: an exclusive lock with the cycle's own semantics stands in.
        with (self.admin / 'backup-cycle.lock').open('a') as stream:
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise trust.Refused('Another backup cycle step is active.') from None
            yield

    def reconcile_services(self):
        """No systemd units exist here; repointing them is covered by the deployment tests."""


class Chain:
    def __init__(self, stack, keys):
        self.stack, self.private, self.public, self.fingerprint = stack, *keys
        self.work = stack.work

    def upgrader(self, target):
        return ChainUpgrade(target, repository=ROOT, admin=self.stack.admin, bundles=self.stack.bundles).load()

    def rollback(self, target):
        return RollbackRehearsal(target, repository=ROOT, admin=self.stack.admin, bundles=self.stack.bundles).load()

    def pointer(self):
        return json.loads((self.stack.admin / upgrade.UPGRADES / upgrade.ACTIVE).read_text())

    def active(self):
        """The active Compose file and the recovery actor of its installed revision, as the runbook resolves them."""
        path = self.stack.admin / upgrade.UPGRADES / upgrade.ACTIVE
        if not path.exists():
            return self.stack.accepted_file, self.stack.installed / 'candidate'
        pointer = json.loads(path.read_text())
        return Path(pointer['configurationFile']), self.stack.bundles / f'{pointer["revision"][:12]}-private' / 'candidate'

    def recovery(self, command, *args, check=True):
        file, source = self.active()
        receipt = self.work / 'receipts' / f'{command}-{secrets.token_hex(4)}.json'
        return base.shell(['python3', '-I', source / 'deploy/public-production/bin/recovery', command, '--config', file,
                           '--project', self.stack.project, '--state', self.stack.admin / 'first-install/recovery',
                           '--receipt', receipt, *args], check=check, timeout=3600), receipt

    def compose(self, *args, **kwargs):
        return self.stack.compose(self.active()[0], *args, **kwargs)

    def sql(self, database, statement):
        return json.loads(self.rollback_probe().recovery.sql(database, statement))

    def rollback_probe(self):
        return self.upgrader(self.pointer()['revision'])

    def upgrade(self, target, label):
        """The runbook's fenced sequence with a real pair, a real isolated restore and a simulated off-host receipt."""
        actor = self.upgrader(target)
        plan = actor.plan()
        self.stack.step(f'{label}Plan', {'activation': plan['activation'], 'refusals': plan['handoverRefusals'],
                                         'pending': plan['flyway']['pending'], 'changedServices': sorted(plan['changedServices'])})
        actor.stage(plan['planSha256'])
        pair = self.work / 'backups' / label
        self.recovery('backup', '--keyring', self.public, '--recipient', self.fingerprint, '--destination', pair)
        manifest = json.loads((pair / 'manifest.json').read_text())
        restore = self.work / f'restore-{label}.json'
        base.shell(['python3', ROOT / 'scripts/rehearse-private-production-upgrade.py', '--restore-check', '--backup', pair,
                    '--keyring', self.private, '--receipt', restore], timeout=3700)
        offhost = self.work / f'offhost-{label}.json'
        offhost.write_text(json.dumps({'kind': 'encrypted-pull', 'backupId': manifest['backupId'], 'outcome': 'success', 'simulated': True}))
        for path in (offhost, restore):
            path.chmod(0o600)
        started = time.monotonic()
        done = self.upgrader(target).apply(plan['planSha256'], backup=pair, offhost=offhost, restore=restore,
                                           reference=f'rehearsal 488 {label}')
        require(done['mode'] == 'applied', f'The {label} upgrade did not complete.')
        self.stop_degraded(done['optionalDegraded'])
        verified = self.upgrader(target).verify()
        self.stack.step(f'{label}Applied', {'seconds': round(time.monotonic() - started), 'migration': done['migration'],
                                            'optionalDegraded': done['optionalDegraded'], 'verified': verified['mode']})
        return plan

    def stop_degraded(self, names):
        # The rehearsal supplies no translation model, so the localizer can never become healthy. An
        # optional service may be stopped (#452); a running unhealthy one would read as drift.
        if names:
            self.compose('stop', '--timeout', '5', *names)

    def admin(self):
        config = json.loads(self.active()[0].read_text())
        secret = config['secrets']['keycloak_admin_password']['file']
        return base.Admin(config['networks']['identity-data']['name'], self.stack.project, 'disposable-admin', Path(secret).read_text().strip())

    def principal(self, admin, username):
        subject = json.loads(admin.call('GET', f'/admin/realms/gameometro/users?username={urllib.parse.quote(username)}&exact=true'))[0]['id']
        return subject, str(__import__('uuid').UUID(bytes=hashlib.md5((base.ISSUER + '\n' + subject).encode()).digest(), version=3))

    def new_user_data(self):
        """Writes the reverted release made: an account, a rating and a live Keycloak session."""
        admin = self.admin()
        user = 'after-release@example.test'
        admin.call('POST', '/admin/realms/gameometro/users', {
            'username': user, 'email': user, 'emailVerified': True, 'enabled': True,
            'credentials': [{'type': 'password', 'value': self.stack.password, 'temporary': False}]})
        _, principal = self.principal(admin, user)
        probe = self.rollback_probe()
        probe.recovery.sql('videogame_platform', f"INSERT INTO ratings.rating(user_id,game_id,value) "
                                                 f"SELECT '{principal}',game_id,6 FROM catalogue.game ORDER BY game_id LIMIT 1;")
        require(base.login_status(admin, self.stack.users[1], self.stack.password) == 200, 'The session login failed.')
        subject, _ = self.principal(admin, self.stack.users[1])
        sessions = json.loads(admin.call('GET', f'/admin/realms/gameometro/users/{subject}/sessions'))
        require(sessions, 'No Keycloak session was created.')
        self.stack.users.append(user)
        return {'user': user, 'sessionSubject': subject, 'sessions': len(sessions),
                'ratings': probe.query('videogame_platform', upgrade.DATA_SQL)['ratings']}

    def edge_poller(self, seconds):
        config = json.loads(self.active()[0].read_text())
        name = f'{self.stack.project}-poller'
        base.shell(['docker', 'run', '-d', '--name', name, '--network', config['networks']['telemetry']['name'],
                    '--label', f'vgp.production-test={self.stack.project}', '--read-only', '--cap-drop', 'ALL',
                    '-v', f'{install.TLS}/ca.crt:/run/private-tls/ca.crt:ro', install.HELPER, 'python3', '-c', POLLER, str(seconds)])
        return name

    def availability(self, name):
        lines = base.shell(['docker', 'logs', name], check=False).stdout.decode().splitlines()
        base.shell(['docker', 'rm', '-f', name], check=False)
        samples = [json.loads(line) for line in lines if line.startswith('[')]
        failed = [t for t, s in samples if s not in (200, 429)]
        return {'requests': len(samples), 'failed': len(failed), 'rateLimited': sum(s == 429 for _, s in samples),
                'failedWindowSeconds': round(failed[-1] - failed[0], 1) if failed else 0.0,
                'statuses': sorted({str(s) for _, s in samples})}

    def killed_rollback(self, target, sha, phase):
        """A real controller killed at phase, with no chance to clean up."""
        RollbackRehearsal.kill = (phase, 0)
        sys.stdout.flush()
        pid = os.fork()
        if pid == 0:
            try:
                self.rollback(target).apply(sha, 'rehearsal 488 kill')
            except BaseException:
                pass
            os._exit(77)
        _, status = os.waitpid(pid, 0)
        RollbackRehearsal.kill = None
        require(os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL, f'The controller was not killed: {status}')

    def image_of(self, service):
        identity = self.compose('ps', '-q', service).stdout.decode().strip()
        return json.loads(base.shell(['docker', 'inspect', identity]).stdout)[0]['Config']['Image'], identity


def resolve(revisions):
    """The exact publications each revision's trusted-main runs retain."""
    api = trust.GitHub(base.shell(['gh', 'auth', 'token']).stdout.decode().strip())
    return {name: integration.resolve(api, sha)['images'] for name, sha in revisions.items()}


def refresh_truststore(images):
    """The owner's revalidated store: every target JVM's anchors plus the private CA, as the upgrader requires."""
    tls = install.TLS
    for image in images:
        base.shell(['docker', 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}', '-v', f'{tls}:/t',
                    '--entrypoint', 'sh', image, '-ec',
                    'keytool -importkeystore -noprompt -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit '
                    '-destkeystore /t/truststore.p12 -deststoretype PKCS12 -deststorepass changeit >/dev/null 2>&1 || true'])
    (tls / 'truststore.p12').chmod(0o644)


def exercise(stack, revisions):
    lkg, current, incompatible = revisions['lastKnownGood'], revisions['current'], revisions['incompatible']
    publications = resolve(revisions)
    stack.step('publications', {k: {n: v.rsplit('@', 1)[-1][:19] for n, v in images.items()} for k, images in publications.items()})
    print('Installing the signed candidate in an isolated project.', flush=True)
    stack.install()
    stack.start()
    for images in publications.values():
        base.shell(['docker', 'pull', '-q', images['application']], timeout=900)
    refresh_truststore([images['application'] for images in publications.values()])
    (stack.work / 'backups').mkdir(mode=0o700)
    chain = Chain(stack, base.keyrings(stack.work))

    print(f'Upgrading to the last-known-good release {lkg[:12]}.', flush=True)
    chain.upgrade(lkg, 'lastKnownGood')
    print(f'Upgrading to the release to revert, {current[:12]}.', flush=True)
    plan = chain.upgrade(current, 'current')
    require(plan['activation'] == 'fenced' and 'another-service-changes' in plan['handoverRefusals'],
            'Expected the rebuilt dependencies to force the fenced activation.')
    stack.step('newUserData', data := chain.new_user_data())

    print('Assessing the rollback.', flush=True)
    assessment = chain.rollback(lkg).assess()
    require(assessment['verdict'] == rollback.ALLOW, f"Expected ALLOW: {assessment['reasons']}")
    require(set(assessment['keptServices']) == {'keycloak', 'caddy', 'catalogue-localizer'}, 'Unexpected kept services.')
    require(set(assessment['changedServices']) == {'application', 'migration'}, 'Unexpected changed services.')
    stack.step('assessment', {k: assessment[k] for k in ('verdict', 'assessmentSha256', 'changedServices', 'flyway', 'realmImport',
                                                       'persistedContractPaths')} | {'kept': sorted(assessment['keptServices'])})
    sha = assessment['assessmentSha256']
    require(chain.rollback(lkg).assess()['assessmentSha256'] == sha, 'The assessment is not deterministic.')
    stack.step('otherTargetRefused', chain.rollback(prepare.REVISION).assess()['reasons'])

    with (stack.admin / 'first-install/recovery/LOCK').open('a') as held:
        fcntl.flock(held, fcntl.LOCK_EX)
        stack.step('heldRecoveryLockRefused', base.refused(chain.rollback(lkg).apply, sha, 'rehearsal 488'))
    require(chain.pointer()['revision'] == current and not chain.rollback(lkg).rollbacks(), 'A refused rollback changed state.')

    print('A handled fault in the smoke phase.', flush=True)
    RollbackRehearsal.fault = 'smoke'
    stack.step('smokeFaultReason', base.refused(chain.rollback(lkg).apply, sha, 'rehearsal 488 fault'))
    record = json.loads(chain.rollback(lkg).rollbacks()[-1].read_text())
    require(record['status'] == 'undone' and record['containment'] == 'confirmed', f"Unexpected fault journal: {record['status']}")
    image, _ = chain.image_of('application')
    require(image == publications['current']['application'] and chain.pointer()['revision'] == current,
            'The release that was running does not serve again.')
    stack.step('smokeFaultUndone', {'failedPhase': record['failedPhase'], 'containment': record['containment']})

    print('SIGKILL with the candidate serving, then abandon.', flush=True)
    chain.killed_rollback(lkg, sha, 'replacing')
    probe = chain.rollback(lkg)
    record = json.loads(probe.rollbacks()[-1].read_text())
    require(record['status'] == 'running' and record['phase'] == 'replacing', 'Unexpected journal after SIGKILL.')
    require(probe.candidate_running(), 'The candidate was expected to serve after the kill.')
    blocked = probe.assess()['reasons']
    require('unfinished-operation' in blocked and upgrade.Upgrade.unfinished(probe), 'The unfinished rollback blocks nothing.')
    stack.step('blockedWhileUnfinished', {'rollback': blocked, 'upgraderSeesJournal': True})
    abandoned = chain.rollback(lkg).abandon()
    image, _ = chain.image_of('application')
    require(abandoned['mode'] == 'undone' and image == publications['current']['application']
            and not chain.rollback(lkg).candidate_running(), 'Abandon did not restore the running release.')
    stack.step('abandonedAfterSigkill', abandoned['mode'])

    print('The authorized rollback, with the edge polled throughout.', flush=True)
    kept_before = {n: chain.image_of(n) for n in ('keycloak', 'caddy', 'postgres')}
    identity_before = chain.sql('videogame_keycloak', upgrade.IDENTITY_SQL)
    flyway_before = base.flyway_endpoint(probe.recovery)
    poller = chain.edge_poller(900)
    time.sleep(3)
    started = time.monotonic()
    result = chain.rollback(lkg).apply(sha, 'rehearsal 488 owner')
    seconds = round(time.monotonic() - started)
    time.sleep(3)
    availability = chain.availability(poller)
    stack.step('rolledBack', {**{k: result[k] for k in ('mode', 'targetRevision', 'keptServices')}, 'seconds': seconds,
                              'edgeAvailability': availability})

    print('Verifying what the rollback kept.', flush=True)
    verified = chain.upgrader(lkg).verify()
    image, _ = chain.image_of('application')
    require(verified['mode'] == 'verified' and image == publications['lastKnownGood']['application'], 'The last-known-good release does not serve.')
    for name, (kept_image, identity) in kept_before.items():
        require(chain.image_of(name) == (kept_image, identity), f'{name} was recreated or replaced.')
    data_after = chain.rollback_probe().query('videogame_platform', upgrade.DATA_SQL)
    require(data_after['ratings'] == data['ratings'], 'Ratings written by the reverted release were lost.')
    admin = chain.admin()
    require(all(base.login_status(admin, user, stack.password) == 200 for user in stack.users), 'An account no longer signs in.')
    sessions = json.loads(admin.call('GET', f"/admin/realms/gameometro/users/{data['sessionSubject']}/sessions"))
    require(len(sessions) >= data['sessions'], 'A live Keycloak session ended.')
    identity_after = chain.sql('videogame_keycloak', upgrade.IDENTITY_SQL)
    changed = sorted(k for k in upgrade.CONFIGURATION_DIGESTS if identity_before[k] != identity_after[k])
    require(not changed and identity_before['realm'] == identity_after['realm'], f'Keycloak configuration changed: {changed}')
    require(base.flyway_endpoint(chain.rollback_probe().recovery) == flyway_before, 'Flyway history changed.')
    for name, value in stack.volumes.items():
        require(base.shell(['docker', 'volume', 'inspect', '--format', '{{.CreatedAt}}', name]).stdout.decode().strip() == value,
                'A durable volume was replaced.')
    pointer = chain.pointer()
    reverted = json.loads((stack.admin / upgrade.UPGRADES / current / upgrade.JOURNAL).read_text())
    require(pointer['rolledBackFrom']['revision'] == current and reverted['status'] == 'reverted', 'Lineage was not recorded.')
    stack.step('preserved', {'ratings': data_after['ratings'], 'accounts': len(stack.users), 'keycloakSessions': len(sessions),
                             'flyway': flyway_before['version'], 'keptContainers': sorted(kept_before),
                             'keycloakConfigurationUnchanged': True, 'volumesUnchanged': len(stack.volumes)})
    stack.step('secondRollbackRefused', chain.rollback(lkg).assess()['reasons'])

    print(f'Upgrading the rolled-back runtime to {incompatible[:12]}, which adds a migration.', flush=True)
    chain.upgrade(incompatible, 'incompatible')
    forward = chain.rollback(lkg).assess()
    require(forward['verdict'] == rollback.FORWARD and 'schema-advanced' in forward['reasons']
            and 'last-known-good-unverified' not in forward['reasons'], f"Expected only a forward fix: {forward['reasons']}")
    stack.step('incompatibleRollbackRefused', {'verdict': forward['verdict'], 'reasons': forward['reasons'],
                                               'applyRefused': base.refused(chain.rollback(lkg).apply, forward['assessmentSha256'], 'rehearsal 488')})
    stack.report['outcome'] = 'success'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--last-known-good', required=True, help='Trusted-main SHA the rollback returns to')
    parser.add_argument('--current', required=True, help='Later trusted-main SHA, application-only compatible with it')
    parser.add_argument('--incompatible', required=True, help='Later trusted-main SHA that adds a migration')
    parser.add_argument('--evidence', type=Path, required=True, help='New protected directory for the sanitized report')
    args = parser.parse_args()
    revisions = {'lastKnownGood': args.last_known_good, 'current': args.current, 'incompatible': args.incompatible}
    require(all(re.fullmatch('[0-9a-f]{40}', v) for v in revisions.values()), 'Full lowercase SHAs are required.')
    os.umask(0o077)
    args.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    integration.verify_local_docker()
    work = Path(tempfile.mkdtemp(prefix='vgp-rollback-rehearsal-', dir=os.environ.get('VGP_REHEARSAL_DIR'))).resolve()
    work.chmod(0o755)
    stack = base.Stack(work, args.current, args.evidence)
    stack.patch()
    stack.report.update(kind='production-rollback-rehearsal', revisions=revisions)
    stack.report['notExercised'] = [
        *stack.report['notExercised'],
        'BFF browser sign-in, BFF sessions and CSRF across the switch (in-memory BFF sessions end by design, ADR-0027)',
        'the backup-cycle lock and backup-unit repointing (stand-ins here; covered by the deployment tests)',
        'operator publication and offline fingerprint verification (unpublished working tree)',
        'root ownership of the empty secret files an upgrade adds (the rehearsal runs unprivileged)',
        'the GitHub compare check of upgrade ancestry (its response exceeds the bound over this range; local Git proves it)',
        'the public edge and its private twin (this stack is private; the unit tests cover the twin)']
    started = time.monotonic()
    try:
        exercise(stack, revisions)
        return 0
    except BaseException:
        stack.report['failure'] = traceback.format_exc(limit=6)[-1800:]
        print(stack.report['failure'], file=sys.stderr)
        return 1
    finally:
        stack.report['durationSeconds'] = round(time.monotonic() - started)
        stack.report['sourceFiles'] = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in
                                       ('scripts/production_rollback.py', 'scripts/private_production_upgrade.py',
                                        'scripts/rehearse-production-rollback.py')}
        (args.evidence / 'report.json').write_text(json.dumps(stack.report, indent=2) + '\n')
        if os.environ.get('VGP_REHEARSAL_KEEP') != '1':
            stack.cleanup()
            shutil.rmtree(work, ignore_errors=True)
        print(json.dumps({'outcome': stack.report['outcome'], 'report': str(args.evidence / 'report.json')}))


if __name__ == '__main__':
    raise SystemExit(main())
