#!/usr/bin/env python3
"""Exact-main admission and restricted private-production deployment actor (#467)."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tarfile
import tempfile
import time

import private_dev_promotion as trust
import public_production_integration as integration
import install_private_production as install
import private_production_upgrade as upgrade
import private_production_admin as admin
import production_deployment_evidence as evidence

ROOT = Path(__file__).resolve().parents[1]
CONFIG = Path('/etc/gameometro/production/deploy.json')
WORKFLOW = '.github/workflows/deploy-production.yml'
BACKUP_UNITS = Path('/etc/systemd/system')
OPERATIONS = Path('/srv/gameometro/operations')
EXPORT = ('deploy/', 'docker/', 'scripts/', 'tools/', 'backend/src/main/resources/db/migration/')
require = trust.require
PREFLIGHT_CHECKS = ('access', 'recovery-ready', 'installed-runtime', 'backup-cycle', 'backup-lock',
                    'backup-runtime', 'host-s3-admission', 'backup-services', 'upgrade-plan',
                    'plan-prerequisites', 'recovery-capacity')
FAILURE_CATEGORIES = ('missing-file', 'permission-denied', 'timeout', 'interrupted', 'check-refused', 'unexpected')


def failure_category(error):
    # Fixed vocabulary only: exception text, child output and file paths remain private.
    for kind, name in ((FileNotFoundError, 'missing-file'), (PermissionError, 'permission-denied'),
                       ((TimeoutError, subprocess.TimeoutExpired), 'timeout'), (KeyboardInterrupt, 'interrupted'),
                       (trust.Refused, 'check-refused')):
        if isinstance(error, kind):
            return name
    return 'unexpected'


def identity(command):
    match = re.fullmatch(r'(plan|prepare|apply) ([0-9a-f]{40}) ([1-9][0-9]{0,19}) (1)', command)
    require(match is not None, 'Only a fresh production plan/deploy invocation is allowed; reruns require inspection.')
    return match.groups()


def environment(api, name='production'):
    value = api.request(f'/environments/{name}')
    rules = value.get('protection_rules', [])
    reviewers = [r for r in rules if r.get('type') == 'required_reviewers']
    require(value.get('can_admins_bypass') is False and len(reviewers) == 1
            and reviewers[0].get('prevent_self_review') is False
            and [(r.get('type'), r.get('reviewer', {}).get('login')) for r in reviewers[0].get('reviewers', [])] == [('User', 'rubhern')]
            and all(r.get('type') in ('required_reviewers', 'branch_policy') for r in rules)
            and value.get('deployment_branch_policy') == {'protected_branches': False, 'custom_branch_policies': True},
            'production requires owner approval, self-review permitted, no bypass and selected main only.')
    branches = api.request(f'/environments/{name}/deployment-branch-policies?per_page=100')
    require(branches.get('total_count') == 1 and
            [(b.get('name'), b.get('type')) for b in branches['branch_policies']] == [('main', 'branch')],
            'production must permit only the main branch.')


def dispatch(api, revision, run_id, attempt, *, allow_waiting=False):
    trust.validate_source_run(revision, run_id, attempt)
    require(str(attempt) == '1', 'Do not rerun a deployment; inspect and dispatch a fresh attempt.')
    value = api.request(f'/actions/runs/{run_id}')
    states = ('in_progress', 'waiting') if allow_waiting else ('in_progress',)
    require(value.get('head_sha') == revision and value.get('head_branch') == 'main'
            and value.get('path') == WORKFLOW and value.get('event') == 'workflow_dispatch'
            and value.get('head_repository', {}).get('full_name') == trust.REPOSITORY
            and value.get('actor', {}).get('login') == 'rubhern'
            and value.get('triggering_actor', {}).get('login') == 'rubhern'
            and value.get('status') in states and value.get('run_attempt') == 1,
            'Only the active owner dispatch of the exact main revision is authorized.')
    return value


def owner_approved(reviews, name):
    return any(r.get('state') == 'approved' and r.get('user', {}).get('login') == 'rubhern'
               and any(e.get('name') == name for e in r.get('environments', [])) for r in reviews)


def approved(api, run_id, name='production'):
    require(owner_approved(api.request(f'/actions/runs/{run_id}/approvals'), name),
            'This run has no production approval by the owner.')


def wait_for_approvals(api, revision, run_id, attempt, *, clock=time.monotonic, sleep=time.sleep):
    """Allow GitHub to release the two protected jobs in either order."""
    deadline = clock() + 600
    while True:
        run = dispatch(api, revision, run_id, attempt, allow_waiting=True)
        if run['status'] == 'in_progress':
            reviews = api.request(f'/actions/runs/{run_id}/approvals')
            if all(owner_approved(reviews, name) for name in ('production', 'production-recovery')):
                return
        remaining = deadline - clock()
        require(remaining > 0, 'Both owner approvals are required within ten minutes.')
        sleep(min(5, remaining))


def admission(api, revision, run_id, attempt, host=False):
    environment(api)
    environment(api, 'production-recovery')
    dispatch(api, revision, run_id, attempt)
    if host:
        approved(api, run_id)
        approved(api, run_id, 'production-recovery')
    return integration.resolve(api, revision)


def invoke(args, *, data=None, timeout=300):
    # Existing bounded cancellation primitive terminates the whole local process group.
    # No shell, child diagnostics or secret-bearing output is forwarded.
    return upgrade.control.run(args, data=data, timeout=timeout)


def read_config():
    install.secure(CONFIG, mode=0o600)
    require(CONFIG.stat().st_size <= 65536, 'Oversized deployment configuration.')
    value = json.loads(CONFIG.read_text())
    require(set(value) == {'githubTokenFile', 'backupCycleConfig', 'sourceTag', 'policySha256', 'accessEvidenceFile'}, 'Unexpected deployment configuration fields.')
    for name in ('githubTokenFile', 'backupCycleConfig', 'accessEvidenceFile'):
        install.secure(Path(value[name]), mode=0o600)
    require(re.fullmatch('[0-9a-f]{64}', value['policySha256']) and
            value['sourceTag'] == 'tag:gameometro-production-deploy', 'Invalid deployment identity or policy.')
    return value


def peer(config):
    connection = os.environ.get('SSH_CONNECTION', '').split()
    require(len(connection) == 4, 'SSH peer is required.')
    value = json.loads(invoke(['/usr/bin/tailscale', 'whois', '--json', connection[0]]))
    require(config['sourceTag'] in value.get('Node', {}).get('Tags', []), 'Unexpected deployment tailnet identity.')


def access(config):
    evidence = json.loads(Path(config['accessEvidenceFile']).read_text())
    # Explicit owner attestations; these do not pretend to prove effective ACLs from a hash.
    require(evidence == {'policySha256': config['policySha256'], 'ownerOnlyAdmin': True,
                         'deploymentDeniedAdminIPv4': True, 'deploymentDeniedAdminIPv6': True,
                         'nonOwnerDeniedAdmin': True, 'publicDeniedAdmin': True,
                         'temporaryGrafanaRetired': True}, 'Owner access acceptance is missing or differs from the policy.')
    admin.checked(admin.status())
    if not admin.STATE.exists():
        # A temporary bridge must be retired once during authorized bootstrap, never killed by CI.
        for _, backend in admin.ENDPOINTS.values():
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
                listener.bind(('127.0.0.1', backend))
    invoke(['/usr/bin/socat', '-V'])


def bootstrap(config, revision, run_id, attempt, mode):
    """Installed immutable entrypoint admits the publication before running target code as root."""
    peer(config)
    token = Path(config['githubTokenFile']).read_text().strip()
    os.environ['GH_TOKEN'] = token  # Dedicated read credential, never root's global gh login.
    api = trust.GitHub(token)
    admission(api, revision, run_id, attempt, host=True)
    tree = api.request(f'/git/trees/{revision}?recursive=1')
    require(not tree.get('truncated', True) and len(tree['tree']) <= 10000, 'Incomplete operational tree.')
    expected = {e['path']: e for e in tree['tree'] if e['type'] != 'tree' and e['path'].startswith(EXPORT)}
    require(sum(e.get('size', 0) for e in expected.values()) <= 256 * 1024 * 1024, 'Operational source exceeds the export bound.')
    repository = upgrade.REPOSITORY
    install.secure(repository, directory=True)
    invoke(['git', '-C', repository, 'fetch', '--no-tags', 'origin', revision])
    require(invoke(['git', '-C', repository, 'rev-parse', revision]).decode().strip() == revision, 'Fetched SHA differs.')
    destination = OPERATIONS / revision
    destination.parent.mkdir(mode=0o700, exist_ok=True)
    install.secure(destination.parent, directory=True)
    materialize(repository, revision, tree, destination)
    install.secure(destination, mode=0o700, directory=True)
    result = subprocess.run(['python3', '-B', str(destination / 'scripts/production_deploy.py'), '--host', mode,
                             revision, run_id, attempt], capture_output=True, timeout=10500)
    require(len(result.stdout) <= 2 * 1024 * 1024, 'Oversized actor response.')
    # Preserve the child's sanitized failure receipt and exit status, never stderr.
    return result


def materialize(repository, revision, tree, destination):
    expected = {e['path']: e for e in tree['tree'] if e['type'] != 'tree' and e['path'].startswith(EXPORT)}
    # Export the exact verified Git tree; never checkout a mutable main branch.
    if not destination.exists():
        with tempfile.TemporaryFile(dir=destination.parent) as archive:
            subprocess.run(['git', '-C', str(repository), 'archive', revision, *[p.rstrip('/') for p in EXPORT]], stdout=archive,
                           stderr=subprocess.DEVNULL, timeout=120, check=True)
            require(archive.tell() <= 272 * 1024 * 1024, 'Operational archive exceeds its bound.')
            archive.seek(0)
            with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
                with tarfile.open(fileobj=archive) as bundle:
                    members = bundle.getmembers()
                    require(len(members) <= 10000 and all(m.isfile() or m.isdir() for m in members), 'Unsafe operational archive.')
                    bundle.extractall(temporary, filter='data')
                os.rename(temporary, destination)
    # Check every exported file, including the entrypoint and all its imports.
    observed = {p.relative_to(destination).as_posix() for p in destination.rglob('*') if not p.is_dir()}
    require(observed == set(expected), 'Exported operational source inventory differs.')
    import hashlib
    for name, entry in expected.items():
        path = destination / name
        require(not path.is_symlink() and entry['type'] == 'blob' and entry['mode'] in ('100644', '100755'), 'Unsafe source entry.')
        data = path.read_bytes()
        require(hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest() == entry['sha'], 'Operational source bytes differ.')
        require(bool(path.stat().st_mode & 0o111) == (entry['mode'] == '100755'), 'Operational source mode differs.')


def load_cycle(path):
    import importlib.machinery
    loader = importlib.machinery.SourceFileLoader('deployment_backup_cycle', str(ROOT / 'deploy/public-production/bin/backup-cycle'))
    module = loader.load_module()
    core = module.load_core()
    return module.Cycle(module.load_configuration(core, path), core).load()


def backup_units(config_path, target_source, apply=False):
    """Retarget only the two existing backup service ExecStarts; never touch timers."""
    units = []
    for name, command in (('gameometro-backup.service', 'run'), ('gameometro-backup-reconcile.service', 'reconcile')):
        path = BACKUP_UNITS / name
        install.secure(path, mode=0o644)
        fragment = invoke(['/usr/bin/systemctl', 'show', name, '--property=FragmentPath', '--value']).decode().strip()
        drops = invoke(['/usr/bin/systemctl', 'show', name, '--property=DropInPaths', '--value']).strip()
        require(fragment == str(path), 'Backup service has an unexpected source.')
        override = path.parent / (name + '.d') / 'keyring.conf'
        require(drops.decode().split() in ([], [str(override)]), 'Backup service has an unreviewed override.')
        if drops:
            install.secure(override, mode=0o644)
            require(override.stat().st_size <= 4096, 'Oversized backup override.')
            directives = [line.strip() for line in override.read_text().splitlines()
                          if line.strip() and not line.lstrip().startswith(('#', ';'))]
            require(directives == ['[Service]', 'ReadWritePaths=/etc/gameometro/production/backup-public-keyring'],
                    'Only the approved #471 keyring write-path override is allowed.')
        text = path.read_text()
        lines = re.findall(r'^ExecStart=(.+)$', text, re.M)
        require(len(lines) == 1, 'Unexpected backup service command inventory.')
        allowed = (r'/usr/bin/python3 (?:/srv/gameometro/installations/[0-9a-f]{12}-private/candidate|/opt/gameometro/runtime)'
                   + r'/deploy/public-production/bin/backup-cycle ' + command + r' --config ' + re.escape(str(config_path)))
        require(re.fullmatch(allowed, lines[0]), 'Installed backup service command differs from the reviewed pattern.')
        replacement = '/usr/bin/python3 ' + str(target_source / 'deploy/public-production/bin/backup-cycle') + ' ' + command + ' --config ' + str(config_path)
        units.append((path, text, text.replace('ExecStart=' + lines[0], 'ExecStart=' + replacement)))
    if apply:
        # Validate BOTH units before changing either. A partial update is recoverable and never thaws.
        for path, before, after in units:
            require(path.read_text() == before, 'Backup service drifted before reconciliation.')
            if before != after:
                admin.write_unit(path, after)
        invoke(['/usr/bin/systemctl', 'daemon-reload'])
    return units


class Rollout:
    def __init__(self, config, revision, run_id, attempt):
        self.config, self.revision = config, revision
        self.reference = f'Actions:{run_id}:attempt:{attempt}'
        self.directory = install.ADMIN / 'deployments' / f'{run_id}-{attempt}'
        self.record = {'revision': revision, 'runId': run_id, 'attempt': attempt, 'outcome': 'failure', 'phase': 'preflight'}

    def save(self, phase):
        if phase not in ('preflight', 'planned'):
            self.record.pop('check', None)
        self.record['phase'] = phase
        upgrade.control.durable(self.directory / 'deployment.json', self.record)

    def check(self, name):
        require(name in PREFLIGHT_CHECKS, 'Unknown preflight check.')
        self.record['check'] = name
        self.save(self.record['phase'])

    def run(self, mode):
        require(not self.directory.exists(), 'This deployment already has a journal; inspect it before a fresh dispatch.')
        self.directory.mkdir(mode=0o700, parents=True)
        self.save('preflight')
        self.check('access')
        access(self.config)
        api = trust.GitHub(os.environ['GH_TOKEN'])
        self.check('recovery-ready')
        proof = evidence.read(api, 'ready', self.revision, self.record['runId'], self.record['attempt'])
        require(proof.get('kind') == 'recovery-ready' and proof.get('outcome') == 'success'
                and re.fullmatch('[0-9a-f]{64}', proof.get('nonce', '')), 'Recovery runner readiness is unproven.')
        self.check('installed-runtime')
        fingerprint = install.operational_fingerprint()
        actor = upgrade.Upgrade(self.revision, operational=self.revision, adapter=fingerprint).load()
        self.check('backup-cycle')
        cycle = load_cycle(Path(self.config['backupCycleConfig']))
        require(cycle.config['admin'] == str(install.ADMIN) and cycle.config['project'] == 'gameometro-production'
                and cycle.config['state'] == str(install.ADMIN / 'first-install/recovery'), 'Backup configuration names another runtime.')
        # Both installed timers use this lock. Do not stop/disable them or invoke their thawing capture.
        self.check('backup-lock')
        with cycle.exclusive():
            require(cycle.journal['status'] == 'idle', 'A scheduled backup is unfinished; let its existing reconciler finish.')
            self.check('backup-runtime')
            runtime = cycle.runtime_factory()
            cycle.preflight(runtime)
            self.check('host-s3-admission')
            admission_receipt = cycle.admission()
            self.check('backup-services')
            backup_units(Path(self.config['backupCycleConfig']), actor.bundle / 'candidate')
            self.check('upgrade-plan')
            plan = actor.plan()
            self.check('plan-prerequisites')
            require(not plan['publishedPorts'] and plan['prerequisites']['noActiveWork']
                    and plan['prerequisites']['keycloakAdministratorAuthenticates'] is True,
                    'A private, idle, authenticated runtime is required.')
            self.record.update(planSha256=plan['planSha256'], version=plan['target']['version'],
                               images=plan['target']['images'], adapterFingerprint=fingerprint,
                               migrations=plan['flyway'], sourceRunId=str(plan['target']['sourceRunId']),
                               sourceRunAttempt=str(plan['target']['sourceRunAttempt']), securityRunId=str(plan['target']['securityRunId']))
            self.save('planned')
            if mode == 'plan':
                self.check('recovery-capacity')
                total = int(runtime.sql('postgres', "SELECT coalesce(sum(pg_database_size(datname)),0)::bigint FROM pg_database WHERE datname IN ('videogame_platform','videogame_keycloak');").decode().strip().split('|')[0])
                require(proof.get('primaryFingerprint') == cycle.config['recipient']
                        and proof.get('tmpfsFreeBytes', 0) >= total + 64 * 1024**2
                        and proof.get('diskFreeBytes', 0) >= 3*total + 512 * 1024**2,
                        'Recovery key or capacity does not cover the current production databases.')
                self.record['outcome'] = 'planned'
                self.save('planned')
                return self.record
            self.record.pop('check', None)
            self.save('planned')
            actor.stage(plan['planSha256'])
            # Recheck against actual PostgreSQL storage sizes before closing any writer.
            total, largest = runtime.sql('postgres', "SELECT coalesce(sum(pg_database_size(datname)),0)::bigint, coalesce(max(pg_database_size(datname)),0)::bigint FROM pg_database WHERE datname IN ('videogame_platform','videogame_keycloak');").decode().strip().split('|')
            resources = {'databaseBytes': int(total), 'largestDatabaseBytes': int(largest)}
            require(proof.get('primaryFingerprint') == cycle.config['recipient']
                    and proof.get('tmpfsFreeBytes', 0) >= int(total) + 64 * 1024**2
                    and proof.get('diskFreeBytes', 0) >= 3 * int(total) + 512 * 1024**2,
                    'Recovery key or capacity does not cover the current production databases.')
            require(evidence.job(api, self.record['runId']).get('status') == 'in_progress',
                    'The prepared recovery runner is no longer running.')
            admission_receipt = cycle.admission()
            fresh = actor.plan(plan['planSha256'])
            require(fresh['planSha256'] == plan['planSha256'] and fresh['prerequisites']['noActiveWork']
                    and fresh['prerequisites']['keycloakAdministratorAuthenticates'] is True,
                    'The runtime or plan changed before capture.')
            require(not upgrade.control.CANCEL.is_set(), 'Deployment interrupted before capture.')
            self.save('capturing')
            pair = self.directory / 'pair'
            with runtime.lock():
                cycle.preflight(runtime)
                with cycle.budget():
                    runtime.backup(pair, Path(cycle.config['keyring']), cycle.config['recipient'])
            self.record['backupId'] = cycle.core.verify(pair)['backupId']
            self.save('publishing')
            offhost = cycle.invoke('publish', cycle.config['uploaderConfig'], '--backup', pair,
                                   '--observer-receipt', admission_receipt)
            publication = json.loads(Path(offhost).read_text())
            require(publication.get('outcome') == 'success' and publication.get('kind') == 's3-publish'
                    and publication.get('backupId') == self.record['backupId'], 'S3 publication did not prove this pair.')
            # Persist both the original S3 acknowledgement and the exact cross-job request.
            upgrade.control.durable(self.directory / 'publish.json', publication)
            request = {**{k: self.record[k] for k in ('revision', 'runId', 'attempt', 'planSha256')},
                       'kind': 'recovery-request', 'outcome': 'success', 'nonce': proof['nonce'],
                       'publication': publication, 'resources': resources}
            upgrade.control.durable(self.directory / 'recovery-request.json', request)
            self.record['requestSha256'] = upgrade.digest(request)
            self.record['nonce'] = proof['nonce']
            self.save('awaiting-restore')
            return request

    def finish(self):
        install.secure(self.directory / 'deployment.json', mode=0o600)
        stored = json.loads((self.directory / 'deployment.json').read_text())
        require(all(stored.get(k) == self.record[k] for k in ('revision', 'runId', 'attempt'))
                and stored.get('phase') == 'awaiting-restore', 'Only this prepared deployment may continue; inspect other phases.')
        self.record = stored
        api = trust.GitHub(os.environ['GH_TOKEN'])
        restored = evidence.read(api, 'result', self.revision, self.record['runId'], self.record['attempt'])
        require(restored.get('kind') == 'isolated-restore' and restored.get('outcome') == 'success'
                and restored.get('backupId') == stored['backupId'] and restored.get('nonce') == stored['nonce']
                and restored.get('requestSha256') == stored['requestSha256'] and restored.get('cleanup') == 'verified',
                'Successful cleaned recovery of the exact prepared backup is required.')
        request = json.loads((self.directory / 'recovery-request.json').read_text())
        require(upgrade.digest(request) == stored['requestSha256'], 'The prepared request drifted.')
        actor = upgrade.Upgrade(self.revision, operational=self.revision, adapter=stored['adapterFingerprint']).load()
        cycle = load_cycle(Path(self.config['backupCycleConfig']))
        with cycle.exclusive():
            require(cycle.journal['status'] == 'idle', 'A scheduled backup is unfinished.')
            restore_path = self.directory / 'restore.json'
            upgrade.control.durable(restore_path, restored)
            pair, offhost = self.directory / 'pair', self.directory / 'publish.json'
            require(not upgrade.control.CANCEL.is_set(), 'Deployment interrupted before apply.')
            self.save('applying')
            actor.apply(stored['planSha256'], backup=pair, offhost=offhost, restore=restore_path, reference=self.reference)
            self.save('verifying')
            verification = actor.verify()
            require(verification.get('mode') == 'verified' and verification.get('targetRevision') == self.revision,
                    'The upgrade is not verified at the selected revision.')
            self.record['services'] = verification['services']
            self.record['mode'] = verification['mode']
            require('grafana' not in verification.get('optionalDegraded', []), 'Grafana needs attention before admin acceptance.')
            self.save('backup-services')
            backup_units(Path(self.config['backupCycleConfig']), actor.bundle / 'candidate', apply=True)
            self.record['backupServices'] = 'reconciled'
            self.save('admin')
            # Subsequent releases verify/reconcile using the original immutable installed operator.
            if admin.STATE.exists():
                installed = json.loads(admin.STATE.read_text())
                script = Path(installed['script'])
                install.secure(script, mode=0o644)
                require(installed['policySha256'] == self.config['policySha256'], 'Installed admin policy differs.')
                invoke(['python3', '-B', script, '--reconcile'])
            else:
                script = ROOT / 'scripts/private_production_admin.py'
                invoke(['python3', '-B', script, '--install', '--operational-revision', self.revision,
                        '--adapter-fingerprint', stored['adapterFingerprint'], '--owner-reference', self.reference,
                        '--policy-sha256', self.config['policySha256']])
            require(json.loads(invoke(['python3', '-B', script, '--verify'])).get('outcome') == 'verified', 'Admin verification failed.')
            self.record.update(outcome='verified', admin='verified', backup='published-and-restored')
            self.save('complete')
            return self.record


PHASES = ('preflight', 'planned', 'capturing', 'publishing', 'awaiting-restore', 'applying', 'verifying', 'backup-services', 'admin', 'complete')


def summarize(path, revision, run_id, attempt, plan_only):
    trust.validate_source_run(revision, run_id, attempt)
    result = {'revision': revision, 'runId': run_id, 'attempt': attempt, 'outcome': 'failure', 'phase': 'unknown'}
    try:
        require(path.is_file() and path.stat().st_size <= 2 * 1024 * 1024, 'Missing/bounded receipt required.')
        value = json.loads(path.read_text())
        require(value.get('revision') == revision and value.get('runId') == run_id and value.get('attempt') == attempt,
                'Receipt belongs to another dispatch.')
        if value.get('kind') == 'recovery-request':
            result['phase'] = 'awaiting-restore'
            raise trust.Refused('Recovery has not authorized apply.')
        require(value.get('phase') in PHASES, 'Unknown deployment phase.')
        result['phase'] = value['phase']
        if value.get('outcome') in ('failure', 'refused-or-failed'):
            for key, vocabulary in (('check', PREFLIGHT_CHECKS), ('category', FAILURE_CATEGORIES)):
                if value.get(key) in vocabulary:
                    result[key] = value[key]
        for key in ('planSha256', 'adapterFingerprint'):
            require(re.fullmatch('[0-9a-f]{64}', value.get(key, '')), 'Missing operational identity.')
            result[key] = value[key]
        require(re.fullmatch('[0-9A-Za-z.-]{1,100}', value.get('version', '')), 'Invalid version.')
        result['version'] = value['version']
        images = value.get('images', {})
        require(set(images) == set(integration.REPOSITORIES), 'Incomplete image inventory.')
        for name, repository in integration.REPOSITORIES.items():
            require(re.fullmatch(re.escape(repository) + '@sha256:[0-9a-f]{64}', images[name]), 'Invalid image identity.')
        result['images'] = images
        for key in ('sourceRunId', 'sourceRunAttempt', 'securityRunId'):
            require(re.fullmatch(r'[1-9][0-9]{0,19}', value.get(key, '')), 'Invalid CI evidence identity.')
            result[key] = value[key]
        migrations = value.get('migrations', {})
        require(set(migrations) == {'currentRank', 'currentVersion', 'targetVersion', 'pending'}
                and type(migrations['currentRank']) is int and 0 <= migrations['currentRank'] <= 2000
                and re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', migrations.get('targetVersion', ''))
                and (migrations['currentVersion'] is None or re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', migrations['currentVersion']))
                and isinstance(migrations['pending'], list) and len(migrations['pending']) <= 2000
                and all(re.fullmatch(r'V[0-9]+_[0-9]+__[A-Za-z0-9_]+\.sql', n) for n in migrations['pending']),
                'Invalid migration evidence.')
        result['migrations'] = migrations
        if plan_only == 'true':
            require(value.get('outcome') == 'planned' and value['phase'] == 'planned', 'Read-only plan not completed.')
            result['outcome'] = 'planned'
        else:
            require(value.get('outcome') == 'verified' and value.get('mode') == 'verified' and value['phase'] == 'complete'
                    and value.get('admin') == 'verified' and value.get('backupServices') == 'reconciled' and value.get('backup') == 'published-and-restored'
                    and re.fullmatch('[0-9a-f]{32}', value.get('backupId', '')), 'Deployment not fully verified.')
            require(isinstance(value.get('services'), list) and set(value['services']) <= set(upgrade.PERSISTENT)
                    and {'application', 'keycloak', 'caddy', 'grafana'} <= set(value['services']), 'Required services not verified.')
            result.update(outcome='verified', mode='verified', backupId=value['backupId'], admin='verified',
                          backup='published-and-restored', backupServices='reconciled', services=value['services'])
        # Compare the host's identities with independently admitted runner evidence.
        expected = json.loads(Path('release.json').read_text())
        require(expected['source_revision'] == revision and expected['images'] == images
                and expected['adapterFingerprint'] == result['adapterFingerprint']
                and str(expected['source_run_id']) == result['sourceRunId']
                and str(expected['source_run_attempt']) == result['sourceRunAttempt']
                and str(expected['securityRunId']) == result['securityRunId'], 'Host/runner release identities differ.')
    except (Exception, KeyboardInterrupt):
        result['outcome'] = 'failure'
    Path('deployment-receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    summary = ('Production deployment: **' + result['outcome'] + '**\n\n'
               + 'Revision: `' + revision + '`; phase: `' + result['phase'] + '`.\n\n'
               + 'See the sanitized receipt for immutable identities. On failure inspect the protected deployment/upgrade journals; do not rerun, thaw or roll back migrations.\n')
    if 'check' in result:
        summary += 'Preflight check: `' + result['check'] + '`; category: `' + result.get('category', 'unexpected') + '`.\n'
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(summary)
    print(summary)
    return 0 if result['outcome'] in ('planned', 'verified') else 1


def connect(revision, run_id, attempt, mode):
    require(mode in ('plan', 'prepare', 'apply'), 'Unknown host operation.')
    identity(f'{mode} {revision} {run_id} {attempt}')
    host = os.environ['DEPLOY_HOST']
    require(re.fullmatch(r'gameometro-prod\.[a-zA-Z0-9.-]+\.ts\.net', host), 'Unexpected production host.')
    directory = Path(os.environ['RUNNER_TEMP']) / 'production-ssh'
    args = ['ssh', '-F', '/dev/null', '-T', '-i', str(directory / 'key'), '-oIdentitiesOnly=yes',
            '-oBatchMode=yes', '-oStrictHostKeyChecking=yes', '-oUserKnownHostsFile=' + str(directory / 'known_hosts'),
            '-oConnectTimeout=30', '-oServerAliveInterval=15', '-oServerAliveCountMax=3',
            'vgp-production-deploy@' + host, f'{mode} {revision} {run_id} {attempt}']
    # Keep SSH stdin OPEN while waiting. /dev/null or communicate() would immediately
    # signal EOF to #455's session watcher and cancel the deployment.
    with (directory.parent / 'production-response.json').open('wb') as output:
        process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.DEVNULL)
        try:
            return process.wait(timeout=10000)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            process.stdin.close()


def runner(mode, revision, run_id, attempt):
    api = trust.GitHub(os.environ['GH_TOKEN'])
    admission(api, revision, run_id, attempt)
    if mode in ('plan', 'prepare'):
        evidence.read(api, 'ready', revision, run_id, attempt, wait_seconds=5400)
    else:
        evidence.read(api, 'result', revision, run_id, attempt, wait_seconds=5400)
    status = connect(revision, run_id, attempt, mode)
    require(status == 0, 'The restricted host operation failed; inspect its protected journal.')
    path = Path(os.environ['RUNNER_TEMP']) / 'production-response.json'
    if mode == 'prepare':
        require(path.stat().st_size <= 65536, 'Oversized recovery request.')
        request = json.loads(path.read_text())
        require(request.get('kind') == 'recovery-request' and request.get('outcome') == 'success'
                and request.get('revision') == revision and request.get('runId') == run_id and request.get('attempt') == attempt,
                'The host did not return the prepared recovery request.')
        Path('recovery-request.json').write_text(json.dumps(request))
        return 0
    return summarize(path, revision, run_id, attempt, 'true' if mode == 'plan' else 'false')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--discover', action='store_true')
    parser.add_argument('--await-approvals', action='store_true')
    parser.add_argument('--receipt', type=Path)
    parser.add_argument('--connect', action='store_true')
    parser.add_argument('--runner', choices=('plan', 'prepare', 'apply'))
    parser.add_argument('--host', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('arguments', nargs='*')
    args = parser.parse_args()
    os.umask(0o077)
    sys.dont_write_bytecode = True
    rollout = None
    try:
        if args.runner:
            return runner(args.runner, *args.arguments)
        if args.connect:
            return connect(*args.arguments)
        if args.receipt:
            return summarize(args.receipt, *args.arguments)
        if args.await_approvals:
            print('Waiting up to ten minutes for both production approvals.', flush=True)
            wait_for_approvals(trust.GitHub(os.environ['GH_TOKEN']), *args.arguments)
            return 0
        if args.discover:
            revision, run_id, attempt = args.arguments
            result = admission(trust.GitHub(os.environ.get('GH_TOKEN')), revision, run_id, attempt)
            result['adapterFingerprint'] = install.operational_fingerprint()
        else:
            require(os.geteuid() == 0, 'Restricted host actor requires root.')
            mode, revision, run_id, attempt = identity(' '.join(args.arguments) if args.host else os.environ.get('SSH_ORIGINAL_COMMAND', ''))
            config = read_config()
            install.secure(install.ADMIN, mode=0o700, directory=True)
            if args.host:
                os.environ['GH_TOKEN'] = Path(config['githubTokenFile']).read_text().strip()
                admission(trust.GitHub(os.environ['GH_TOKEN']), revision, run_id, attempt, host=True)
                upgrade.control.signal_handlers()
                watcher = upgrade.control.watch_session()
                try:
                    rollout = Rollout(config, revision, run_id, attempt)
                    result = rollout.finish() if mode == 'apply' else rollout.run(mode)
                finally:
                    watcher.set()
            else:
                with (install.ADMIN / 'actions-deploy.lock').open('a') as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    child = bootstrap(config, revision, run_id, attempt, mode)
                    print(child.stdout.decode(), end='')
                    return child.returncode
        print(json.dumps(result))
        return 0
    except BaseException as error:
        # Do not serialize exceptions, payloads, child output, environment or local paths.
        failure = {'outcome': 'refused-or-failed', 'advice': 'Inspect the protected deployment and upgrade journals; no automatic retry or thaw.'}
        if rollout:
            failure.update(rollout.record)
            failure['outcome'] = 'failure'
            failure['category'] = failure_category(error)
        print(json.dumps(failure))
        return 1


if __name__ == '__main__':
    sys.exit(main())
