#!/usr/bin/env python3
"""Post-acceptance transition of the installed private runtime. Default: read-only plan.

First installation keeps `restart: no` so an unaccepted or interrupted boot cannot restart itself.
This is the one reviewed step out of that state. It refuses unless the installation journal,
recovery gate, live containers and a fresh paired backup all agree, and it records the transition
before changing Docker. It never reads secret values, opens a gate, publishes a port, runs
migrations other than the single idempotent view file, or starts the one-shot migration actor.
"""
import sys
sys.dont_write_bytecode = True

import argparse
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import re
import time

import install_private_production as install
import prepare_private_production as prepare
import private_dev_promotion as trust
import private_production_control as control
import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
ADMIN = install.ADMIN
RECORD = 'runtime-acceptance.json'
ACCEPTED = 'compose.accepted.json'
POLICY = 'unless-stopped'
PERSISTENT = ('application', 'blackbox', 'caddy', 'catalogue-localizer', 'grafana', 'keycloak',
              'node-exporter', 'postgres', 'prometheus', 'telemetry')
ONE_SHOT = ('migration',)
# Services whose delta needs a new container. Both are optional for product readiness.
RECREATED = ('catalogue-localizer', 'grafana')
LOCALIZER_MEMORY = str(1792 * 1024 * 1024)
DISABLED_PLUGINS = ('elasticsearch,grafana-pyroscope-datasource,influxdb,jaeger,loki,mssql,mysql,'
                    'opentsdb,stackdriver,tempo,zipkin')
DASHBOARD = 'deploy/private-dev/grafana/dashboards/vgp-synchronization.json'
DASHBOARD_TARGET = '/etc/grafana/canonical-dashboards/vgp-synchronization.json'
COVERAGE_VIEW = 'backend/src/main/resources/db/migration/V20261106_120000__observe_catalogue_localization.sql'
BACKUP_MAX_AGE = timedelta(hours=24)
require = trust.require


def accepted_configuration(runtime, operational_root=ROOT):
    """The complete reviewed delta from the installed runtime; everything else stays identical."""
    config = copy.deepcopy(runtime)
    services = config['services']
    require(set(services) == set(PERSISTENT) | set(ONE_SHOT), 'Unexpected installed service inventory.')
    require(all(s.get('restart') == 'no' for s in services.values()),
            'Installed runtime is not the unaccepted restart state.')
    for name in PERSISTENT:
        services[name]['restart'] = POLICY
    services['catalogue-localizer']['mem_limit'] = LOCALIZER_MEMORY
    grafana = services['grafana']
    require('GF_PLUGINS_DISABLE_PLUGINS' not in grafana['environment'], 'Unexpected Grafana plugin policy.')
    grafana['environment']['GF_PLUGINS_DISABLE_PLUGINS'] = DISABLED_PLUGINS
    grafana['volumes'].append(prepare.bind(Path(operational_root) / DASHBOARD, DASHBOARD_TARGET))
    return config


def validate_accepted(runtime, config, operational_root=ROOT):
    """Exact equality makes any other restart, port, privilege or service change a refusal."""
    require(config == accepted_configuration(runtime, operational_root), 'Unreviewed accepted runtime delta.')
    for service in config['services'].values():
        require(not service.get('ports') and not service.get('privileged') and 'network_mode' not in service,
                'Accepted runtime forbids publications, privileges and host networking.')
    require(all(config['services'][n]['restart'] == 'no' for n in ONE_SHOT), 'One-shot actors must not restart.')


def delta(runtime, config):
    """Reviewable per-service changed fields, without values that could carry configuration."""
    result = {}
    for name, service in config['services'].items():
        changed = sorted(k for k in service if service[k] != runtime['services'][name].get(k))
        if changed:
            result[name] = changed
    return result


def canonical(config):
    return json.dumps(config, sort_keys=True, indent=2) + '\n'


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def installation_evidence(admin=ADMIN, *, recovery_pending=False):
    """Fail closed on anything but one recorded, completed, unreconciled first installation."""
    journal = admin / 'first-install'
    intent = json.loads((admin / 'first-install-intent.json').read_text())
    state = json.loads((journal / 'installation.json').read_text())
    run_id = intent['runId']
    require(re.fullmatch('[0-9a-f-]{36}', run_id) and state.get('runId') == run_id, 'Attempt identity differs.')
    require(state.get('outcome') == 'success' and state.get('phase') == 'complete'
            and state.get('leaseCompletion') == 'acknowledged-and-exited', 'Installation did not complete successfully.')
    require(not (admin / f'first-install-failure-{run_id}.json').exists(), 'A failure sidecar dominates this attempt.')
    require(not list(journal.glob('reconciliation-*.json')), 'An interrupted-attempt reconciliation exists.')
    require(not list(admin.glob('first-install*.pending')) and not list(journal.rglob('*.pending')),
            'Incomplete durable writes require inspection.')
    require(recovery_pending or not (journal / 'recovery/PENDING.json').exists(), 'A recovery operation is pending.')
    return {'runId': run_id, 'configuration': intent['configurationSha256']}


def verify_backup(manifest, images, *, now=None):
    """The newly imported data must be protected first; a checksum of old data is not enough."""
    now = now or datetime.now(timezone.utc)
    require(manifest.get('format') == 1 and manifest.get('environment') == 'production'
            and manifest.get('fence') == 'roles-nologin-drained', 'Not a paired production backup manifest.')
    require(set(manifest.get('databases', ())) == {'videogame_platform', 'videogame_keycloak'},
            'The backup does not pair the application and identity databases.')
    require(manifest.get('images') == images, 'Backup images differ from the installed runtime.')
    point = datetime.fromisoformat(manifest['recoveryPoint'])
    require(point.tzinfo is not None and timedelta(0) <= now - point <= BACKUP_MAX_AGE, 'Paired backup is not fresh.')
    require(all(a.get('bytes', 0) > 0 for a in manifest.get('artifacts', {}).values()) and manifest.get('artifacts'),
            'Backup artefacts are missing.')
    return manifest['backupId']


def load_recovery(source, runtime_file, state):
    """The installed candidate's own actor, exactly as the installer loads it."""
    path = source / 'deploy/public-production/bin/recovery'
    loader = importlib.machinery.SourceFileLoader('accepted_private_runtime_recovery', str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)

    def guarded(args, *, data=None, output=None, timeout=1800):
        require(output is None, 'Unsupported transport.')
        return control.run(args, data=data, timeout=min(timeout, 180))
    module.run = guarded
    return module.Runtime(runtime_file, prepare.PROJECT, state)


GATE_OWNER = "import json;o=json.load(open('/gate/INSTALLATION-OWNER.json'));print(o['runId'],o['status'])"


class Transition:
    def __init__(self, admin=ADMIN, operational_root=ROOT, runner=None):
        self.admin, self.root = admin, operational_root
        self.run = runner or control.run

    def load(self, *, recovery_pending=False):
        self.evidence = installation_evidence(self.admin, recovery_pending=recovery_pending)
        bundle = prepare.TARGET
        require(install.sha(bundle / 'compose.private.json') == self.evidence['configuration'],
                'Installed private configuration differs from the installation claim.')
        self.runtime_file = self.admin / 'first-install/runtime.json'
        self.runtime = json.loads(self.runtime_file.read_text())
        labelled = json.loads((bundle / 'compose.private.json').read_text())
        for service in labelled['services'].values():
            service.setdefault('labels', {}).update({control.RUN_LABEL: self.evidence['runId'],
                                                     control.CONFIG_LABEL: self.evidence['configuration']})
        require(self.runtime == labelled, 'Runtime journal differs from the reviewed configuration.')
        self.accepted = accepted_configuration(self.runtime, self.root)
        validate_accepted(self.runtime, self.accepted, self.root)
        self.accepted_sha = digest(canonical(self.accepted))
        self.source = bundle / 'candidate'
        ceilings = sum(int(s['mem_limit']) for n, s in self.accepted['services'].items() if n in PERSISTENT)
        memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines() if ':' in line)
        total = int(memory['MemTotal'].split()[0]) * 1024
        require(ceilings + total // 5 <= total, 'Accepted ceilings leave no platform memory reserve.')

    def host(self, config, path):
        host = install.Host(self.evidence['configuration'], None, None)
        host.run_id, host.config, host.file = self.evidence['runId'], config, path
        host.base = ['docker', 'compose', '-p', prepare.PROJECT, '-f', str(path), '--profile', '*']
        return host

    def plan(self):
        return {'mode': 'plan', 'runId': self.evidence['runId'], 'productionReady': False,
                'acceptedConfigurationSha256': self.accepted_sha,
                'changedFields': delta(self.runtime, self.accepted),
                'recreated': list(RECREATED), 'policyUpdatedInPlace': sorted(set(PERSISTENT) - set(RECREATED)),
                'unchanged': list(ONE_SHOT), 'publishedPorts': []}

    def check_gate(self, recovery):
        require(recovery.gate('check') == b'open', 'Recovery gate is closed.')
        require(recovery.gate('maintenance-check') == b'none', 'Identity maintenance is active.')
        owner = self.run(['docker', 'run', '--rm', '--log-driver', 'none', '--network', 'none', '--read-only',
                          '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '96m',
                          '--pids-limit', '32', '-v', recovery.gate_volume + ':/gate:ro', install.HELPER,
                          'python3', '-c', GATE_OWNER], timeout=60).decode().split()
        require(owner == [self.evidence['runId'], 'released'], 'Gate belongs to another or unfinished attempt.')

    def apply_view(self, recovery):
        # Only this idempotent read contract. A later Flyway run replaces the view with itself.
        sql = (self.root / COVERAGE_VIEW).read_text()
        recovery.sql('videogame_platform', 'BEGIN;\nSET LOCAL ROLE videogame_app_migrator;\n' + sql + 'RESET ROLE;\n'
                     'GRANT SELECT ON catalogue.observability_localization_coverage TO videogame_grafana;\nCOMMIT;\n')

    def apply(self, reviewed_sha, backup, reference, operational_revision, adapter):
        require(reviewed_sha == self.accepted_sha, 'Accepted configuration differs from the reviewed hash.')
        require(re.fullmatch(r'[A-Za-z0-9#:._ -]{1,120}', reference or ''), 'An owner acceptance reference is required.')
        install.secure(backup, mode=0o600)
        record_path, accepted_path = self.admin / RECORD, self.admin / ACCEPTED
        recovery = load_recovery(self.source, self.runtime_file, self.admin / 'first-install/recovery')
        backup_id = verify_backup(json.loads(backup.read_text()), recovery.images)
        api = trust.GitHub(self.run(['gh', 'auth', 'token']).decode().strip())
        install.verify_operational(api, operational_revision, adapter)
        with recovery.lock():
            self.check_gate(recovery)
            previous = json.loads(record_path.read_text()) if record_path.exists() else None
            require(previous is None or previous.get('acceptedConfigurationSha256') == self.accepted_sha,
                    'A different acceptance is already recorded.')
            if previous is None:
                # Nothing changes while a required service has drifted from the reviewed runtime.
                # The two optional recreated services may legitimately be stopped, as after an OOM.
                self.host(self.runtime, self.runtime_file).running(sorted(set(PERSISTENT) - set(RECREATED)))
            record = {'status': 'applying', 'runId': self.evidence['runId'], 'productionReady': False,
                      'configurationSha256': self.evidence['configuration'], 'acceptedConfigurationSha256': self.accepted_sha,
                      'operationalRevision': operational_revision, 'adapterFingerprint': adapter, 'ownerReference': reference,
                      'backupId': backup_id, 'backupManifestSha256': install.sha(backup),
                      'startedAtUnix': int(time.time())}
            control.durable(record_path, record)
            control.durable(accepted_path, self.accepted)
            self.apply_view(recovery)
            host = self.host(self.accepted, accepted_path)
            host.recovery = recovery
            host.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '240', '--pull', 'never', *RECREATED,
                         timeout=300, output=False)
            self.set_policy(host, POLICY)
            host.running(PERSISTENT)
            require(not host.compose('ps', '-q', *ONE_SHOT).split(), 'A one-shot actor is running.')
            recovery.check_lease()
            record.update(status='applied', finishedAtUnix=int(time.time()))
            control.durable(record_path, record)
        return record

    def set_policy(self, host, policy):
        for name in sorted(set(PERSISTENT) - set(RECREATED) if policy == POLICY else PERSISTENT):
            identities = host.compose('ps', '-aq', name).decode().split()
            require(len(identities) == 1, 'Missing or ambiguous installed service.')
            self.run(['docker', 'update', '--restart', policy, identities[0]], output=False)

    def verify(self):
        record = json.loads((self.admin / RECORD).read_text())
        require(record['status'] == 'applied' and record['acceptedConfigurationSha256'] == self.accepted_sha,
                'No completed acceptance matches this runtime.')
        require(json.loads((self.admin / ACCEPTED).read_text()) == self.accepted, 'Intended accepted configuration changed.')
        host = self.host(self.accepted, self.admin / ACCEPTED)
        host.running(PERSISTENT)
        require(not host.compose('ps', '-q', *ONE_SHOT).split(), 'A one-shot actor is running.')
        return {'mode': 'verified', 'runId': record['runId'], 'acceptedConfigurationSha256': self.accepted_sha,
                'restartPolicy': POLICY, 'services': list(PERSISTENT), 'productionReady': False}

    def revert_restart(self):
        """Policy only: no container, gate, ceiling or view changes, so it also works while closed."""
        record_path, accepted_path = self.admin / RECORD, self.admin / ACCEPTED
        record = json.loads(record_path.read_text())
        recovery = load_recovery(self.source, self.runtime_file, self.admin / 'first-install/recovery')
        with recovery.lock():
            intended = copy.deepcopy(self.accepted)
            for name in PERSISTENT:
                intended['services'][name]['restart'] = 'no'
            control.durable(accepted_path, intended)
            record.update(status='restart-reverted', revertedAtUnix=int(time.time()))
            control.durable(record_path, record)
            self.set_policy(self.host(intended, accepted_path), 'no')
        return {'mode': 'restart-reverted', 'runId': record['runId'], 'productionReady': False}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--apply', action='store_true', help='Record the owner acceptance and transition the runtime')
    mode.add_argument('--verify', action='store_true', help='Read-only comparison of live state with the record')
    mode.add_argument('--revert-restart', action='store_true', help='Return persistent services to restart no')
    parser.add_argument('--accepted-configuration-sha256')
    parser.add_argument('--backup-manifest', type=Path, help='Manifest of the fresh paired backup')
    parser.add_argument('--acceptance-reference', help='Owner decision reference, for example an issue comment')
    parser.add_argument('--operational-revision')
    parser.add_argument('--adapter-fingerprint')
    args = parser.parse_args()
    control.signal_handlers()
    try:
        require(os.geteuid() == 0 and sys.platform == 'linux', 'Local Linux root operation required.')
        require(not any(k.startswith('COMPOSE_') for k in os.environ), 'Ambient Compose overrides are forbidden.')
        integration.verify_local_docker()
        install.secure(ADMIN, mode=0o700, directory=True)
        transition = Transition()
        # Removing automatic restart must stay possible while a recovery is pending.
        transition.load(recovery_pending=args.revert_restart)
        if args.apply:
            require(args.backup_manifest is not None, 'A paired backup manifest is required.')
            result = transition.apply(args.accepted_configuration_sha256, args.backup_manifest,
                                      args.acceptance_reference, args.operational_revision, args.adapter_fingerprint)
        elif args.verify:
            result = transition.verify()
        elif args.revert_restart:
            result = transition.revert_restart()
        else:
            result = transition.plan()
        print(json.dumps(result))
        return 0
    except BaseException as error:
        # Only reviewed refusal texts; raw Docker and environment output never reaches the terminal.
        reason = str(error) if isinstance(error, trust.Refused) else 'inspect-locally'
        print(json.dumps({'outcome': 'refused-or-failed', 'reason': reason, 'productionReady': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
