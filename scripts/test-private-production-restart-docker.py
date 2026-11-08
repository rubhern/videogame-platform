#!/usr/bin/env python3
"""Restart-policy semantics on a real dockerd inside a disposable, unpublished container.

Proves the Docker behaviour the post-acceptance transition and the in-place upgrade rely on: daemon
restart and abrupt daemon loss, automatic restart after a crash, intentional stops, containers created
but never started, one-shot actors, the closed recovery gate and the durable PostgreSQL role fence. It
starts no Gameómetro service and is not VPS or host-reboot acceptance. It needs a privileged local
container, so it is invoked by hand rather than by CI.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

import install_private_production as install

ROOT = Path(__file__).resolve().parents[1]
DIND = 'docker:29.8.2-dind'
POSTGRES = re.search(r'^    image: (postgres:[^\s]+)$', (ROOT / 'deploy/public-production/compose.yaml').read_text(), re.M)[1]
SERVICE = "import os,time\nwhile not os.path.exists('/tmp/crash'):\n time.sleep(0.2)\nraise SystemExit(9)\n"


def run(args, *, data=None, timeout=120, check=True):
    result = subprocess.run([str(a) for a in args], input=data, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'{args[:4]} failed: {result.stderr.decode()[-400:]}')
    return result.stdout.decode().strip()


class Daemon:
    def __init__(self):
        self.name = 'vgp-restart-proof-' + uuid.uuid4().hex[:10]

    def docker(self, *args, **kwargs):
        return run(['docker', 'exec', '-i', self.name, 'docker', *args], **kwargs)

    def wait(self):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if subprocess.run(['docker', 'exec', self.name, 'docker', 'info'], capture_output=True).returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError('Disposable daemon did not become ready.')

    def start(self):
        # DOCKER_TLS_CERTDIR empty keeps the daemon on its private Unix socket: nothing listens on TCP.
        run(['docker', 'run', '-d', '--name', self.name, '--privileged', '--network', 'bridge',
             '-e', 'DOCKER_TLS_CERTDIR=', '--label', 'vgp.restart-proof=true', '--entrypoint', 'sh', DIND, '-c',
             # A real host's /run is a tmpfs, so these never outlive it. Here a stale containerd PID can
             # name a live process of the new PID namespace, and the daemon then refuses to start at all.
             'rm -f /var/run/docker.pid /var/run/docker/containerd/containerd.pid; '
             'exec dockerd-entrypoint.sh dockerd --host unix:///var/run/docker.sock'])
        self.wait()
        image = subprocess.run(['docker', 'save', install.HELPER], capture_output=True, check=True, timeout=300).stdout
        loaded = run(['docker', 'exec', '-i', self.name, 'docker', 'load'], data=image, timeout=300)
        self.image = loaded.splitlines()[-1].split()[-1]

    def load(self, image):
        saved = subprocess.run(['docker', 'save', image], capture_output=True, check=True, timeout=600).stdout
        return run(['docker', 'exec', '-i', self.name, 'docker', 'load'], data=saved, timeout=600).splitlines()[-1].split()[-1]

    def restart_clean(self):
        run(['docker', 'restart', '--time', '30', self.name], timeout=120)
        self.wait()

    def lose_abruptly(self):
        run(['docker', 'kill', '--signal', 'KILL', self.name])
        run(['docker', 'start', self.name])
        self.wait()

    def state(self, container):
        return json.loads(self.docker('inspect', '--format',
                                      '{"running":{{.State.Running}},"exit":{{.State.ExitCode}},"restarts":{{.RestartCount}},'
                                      '"policy":{{json .HostConfig.RestartPolicy.Name}}}', container))

    def remove(self):
        run(['docker', 'rm', '-f', '-v', self.name], check=False)


def proof(daemon):
    checks = {}

    def check(name, condition, observed=None):
        checks[name] = {'passed': bool(condition), 'observed': observed}

    def settle(condition, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition():
                return True
            time.sleep(0.5)
        return False

    def service(name, policy='no'):
        return daemon.docker('run', '-d', '--name', name, '--restart', policy, '--network', 'none', '--read-only',
                             '--tmpfs', '/tmp', daemon.image, 'python3', '-c', SERVICE)

    def crash(name):
        daemon.docker('exec', name, 'touch', '/tmp/crash')

    # First-install state: nothing restarts on its own.
    service('unaccepted-crash')
    crash('unaccepted-crash')
    check('unaccepted crash stays down', settle(lambda: not daemon.state('unaccepted-crash')['running'])
          and daemon.state('unaccepted-crash')['restarts'] == 0, daemon.state('unaccepted-crash'))
    service('unaccepted-running')

    # The transition itself: an in-place policy change of an already running container.
    service('accepted')
    before = daemon.docker('inspect', '--format', '{{.Id}}', 'accepted')
    daemon.docker('update', '--restart', 'unless-stopped', 'accepted')
    state = daemon.state('accepted')
    check('in-place update keeps the running container', state['running'] and state['policy'] == 'unless-stopped'
          and daemon.docker('inspect', '--format', '{{.Id}}', 'accepted') == before, state)
    crash('accepted')
    check('accepted crash restarts automatically', settle(lambda: daemon.state('accepted')['restarts'] >= 1
          and daemon.state('accepted')['running']), daemon.state('accepted'))

    # An operator's stop, including the recovery actor's, is intentional and must hold.
    service('operator-stopped', 'unless-stopped')
    daemon.docker('stop', '--time', '1', 'operator-stopped')
    # Identical to Host.close's last resort.
    service('operator-killed', 'unless-stopped')
    daemon.docker('kill', '--signal', 'KILL', 'operator-killed')
    service('reverted')
    daemon.docker('update', '--restart', 'unless-stopped', 'reverted')
    daemon.docker('update', '--restart', 'no', 'reverted')

    # One-shot actor: completes once and never reruns.
    daemon.docker('run', '-d', '--name', 'one-shot', '--restart', 'no', '--network', 'none', daemon.image, 'python3', '-c', 'pass')
    check('one-shot completes', settle(lambda: not daemon.state('one-shot')['running']))

    # The candidate's real guard under the accepted policy: a closed gate must refuse every restart.
    wrapper = (ROOT / 'deploy/public-production/bin/run-application').read_bytes()
    run(['docker', 'exec', '-i', daemon.name, 'sh', '-c', 'mkdir -p /proof && cat > /proof/run-application && chmod 755 /proof/run-application'], data=wrapper)
    run(['docker', 'exec', '-i', daemon.name, 'sh', '-c', 'printf "#!/bin/sh\\ntouch /evidence/started\\nexec sleep 3600\\n" > /proof/shared && chmod 755 /proof/shared'])
    daemon.docker('volume', 'create', 'gate')
    daemon.docker('volume', 'create', 'evidence')
    daemon.docker('run', '-d', '--name', 'fenced', '--restart', 'unless-stopped', '--network', 'none',
                  '-v', '/proof/run-application:/opt/gameometro/bin/run-application:ro',
                  '-v', '/proof/shared:/opt/gameometro/bin/shared-run-application:ro',
                  '-v', 'gate:/run/recovery', '-v', 'evidence:/evidence',
                  '--entrypoint', '/opt/gameometro/bin/run-application', daemon.image)

    def evidence_absent():
        return daemon.docker('run', '--rm', '--network', 'none', '-v', 'evidence:/evidence', daemon.image,
                             'python3', '-c', "import os;print(os.path.exists('/evidence/started'))") == 'False'
    check('closed gate crash-loops without starting the application',
          settle(lambda: daemon.state('fenced')['restarts'] >= 2) and evidence_absent(), daemon.state('fenced'))

    for mode, interrupt in (('clean daemon restart', daemon.restart_clean), ('abrupt daemon loss', daemon.lose_abruptly)):
        interrupt()
        check(f'{mode}: accepted service returns', settle(lambda: daemon.state('accepted')['running']), daemon.state('accepted'))
        check(f'{mode}: policy survives', daemon.state('accepted')['policy'] == 'unless-stopped')
        check(f'{mode}: running unaccepted service stays down', not daemon.state('unaccepted-running')['running'],
              daemon.state('unaccepted-running'))
        check(f'{mode}: reverted service stays down', not daemon.state('reverted')['running'], daemon.state('reverted'))
        check(f'{mode}: operator stop holds', not daemon.state('operator-stopped')['running'], daemon.state('operator-stopped'))
        check(f'{mode}: operator kill holds', not daemon.state('operator-killed')['running'], daemon.state('operator-killed'))
        check(f'{mode}: one-shot does not rerun', not daemon.state('one-shot')['running'] and daemon.state('one-shot')['restarts'] == 0,
              daemon.state('one-shot'))
        settle(lambda: daemon.state('fenced')['restarts'] >= 1)
        check(f'{mode}: closed gate still refuses', evidence_absent(), daemon.state('fenced'))
    return checks


def upgrade_fence_proof(daemon):
    """The pending upgrade fence against the daemon: real wrappers, gate and PostgreSQL role state."""
    checks = {}

    def check(name, condition, observed=None):
        checks[name] = {'passed': bool(condition), 'observed': observed}

    def settle(condition, seconds=60):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                if condition():
                    return True
            except RuntimeError:
                pass
            time.sleep(0.5)
        return False

    postgres = daemon.load(POSTGRES)
    for name, source in (('run-application', 'deploy/public-production/bin/run-application'),
                         ('run-migrations', 'deploy/public-production/bin/run-migrations')):
        run(['docker', 'exec', '-i', daemon.name, 'sh', '-c', f'mkdir -p /proof && cat > /proof/{name} && chmod 755 /proof/{name}'],
            data=(ROOT / source).read_bytes())
    run(['docker', 'exec', '-i', daemon.name, 'sh', '-c', 'printf "#!/bin/sh\\ntouch /evidence/started\\nexec sleep 3600\\n" > /proof/shared && chmod 755 /proof/shared'])
    for volume in ('upgrade-gate', 'upgrade-evidence', 'upgrade-data'):
        daemon.docker('volume', 'create', volume)

    # The pending state the upgrade leaves after an abrupt death: PENDING and its identity fence, no READY.
    daemon.docker('run', '--rm', '--network', 'none', '-v', 'upgrade-gate:/gate', daemon.image, 'python3', '-c',
                  "import json;open('/gate/PENDING.json','w').close();json.dump({'runId':'r'},open('/gate/IDENTITY-MAINTENANCE.json','w'))")

    def wrapped(name, wrapper, policy, *create):
        daemon.docker(*create, '--name', name, '--restart', policy, '--network', 'none',
                      '-v', f'/proof/{wrapper}:/opt/gameometro/bin/{wrapper}:ro', '-v', f'/proof/shared:/opt/gameometro/bin/shared-{wrapper}:ro',
                      '-v', 'upgrade-gate:/run/recovery:ro', '-v', 'upgrade-evidence:/evidence',
                      '--entrypoint', f'/opt/gameometro/bin/{wrapper}', daemon.image)

    def started():
        return daemon.docker('run', '--rm', '--network', 'none', '-v', 'upgrade-evidence:/evidence', daemon.image,
                             'python3', '-c', "import os;print(os.path.exists('/evidence/started'))") == 'True'

    def gate_files():
        return json.loads(daemon.docker('run', '--rm', '--network', 'none', '-v', 'upgrade-gate:/gate:ro', daemon.image, 'python3', '-c',
                                        "import json,os;print(json.dumps(sorted(os.listdir('/gate'))))"))

    # Manual and automatic starts of each guarded entrypoint, with every container state a fence leaves.
    wrapped('upgrade-stopped-application', 'run-application', 'unless-stopped', 'run', '-d')
    settle(lambda: daemon.state('upgrade-stopped-application')['restarts'] >= 1)
    daemon.docker('stop', '--time', '1', 'upgrade-stopped-application')
    wrapped('upgrade-created-application', 'run-application', 'unless-stopped', 'create')
    wrapped('upgrade-migration-actor', 'run-migrations', 'no', 'run', '-d')
    settle(lambda: not daemon.state('upgrade-migration-actor')['running'])
    check('fence: a started migration actor refuses and exits', daemon.state('upgrade-migration-actor')['exit'] == 1,
          daemon.state('upgrade-migration-actor'))
    check('fence: nothing reached its guarded program', not started())

    # A real database: the role fence and an in-flight migration transaction at the moment of loss.
    daemon.docker('run', '-d', '--name', 'upgrade-postgres', '--restart', 'unless-stopped', '--network', 'none',
                  '-e', 'POSTGRES_PASSWORD=disposable', '-v', 'upgrade-data:/var/lib/postgresql', postgres)

    def psql(statement, user='postgres', database='postgres'):
        return daemon.docker('exec', 'upgrade-postgres', 'psql', '-h', '127.0.0.1', '-XAtq', '-U', user, '-d', database, '-c', statement, check=True)

    def ready():
        return psql('SELECT 1') == '1'
    check('database: ready', settle(ready, 120))
    psql("CREATE ROLE consumer LOGIN PASSWORD 'disposable'")
    psql('CREATE TABLE migrated(id int)')
    psql('ALTER ROLE consumer NOLOGIN')
    daemon.docker('exec', '-d', 'upgrade-postgres', 'psql', '-h', '127.0.0.1', '-U', 'postgres', '-c',
                  'BEGIN; CREATE TABLE in_flight(id int); INSERT INTO migrated VALUES (1); SELECT pg_sleep(600); COMMIT;')
    check('database: a migration transaction is in flight', settle(
        lambda: psql("SELECT count(*) FROM pg_stat_activity WHERE query LIKE '%pg_sleep(600)%' AND pid<>pg_backend_pid()") == '1'))
    daemon.docker('run', '-d', '--name', 'upgrade-in-flight-actor', '--restart', 'no', '--network', 'none', daemon.image, 'sleep', '3600')

    def denied():
        return daemon.docker('exec', '-e', 'PGPASSWORD=disposable', 'upgrade-postgres', 'psql', '-h', '127.0.0.1', '-XAt',
                             '-U', 'consumer', '-d', 'postgres', '-c', 'SELECT 1', check=False) != '1'
    check('database: NOLOGIN refuses the consumer', denied())

    for mode, interrupt in (('abrupt daemon loss', daemon.lose_abruptly), ('clean daemon restart', daemon.restart_clean)):
        interrupt()
        check(f'{mode}: the database returns', settle(ready, 120))
        check(f'{mode}: an in-flight transaction left nothing', psql("SELECT to_regclass('in_flight') IS NULL") == 't'
              and psql('SELECT count(*) FROM migrated') == '0')
        check(f'{mode}: the role fence holds', denied())
        check(f'{mode}: the gate and identity fence persist', gate_files() == ['IDENTITY-MAINTENANCE.json', 'PENDING.json'], gate_files())
        check(f'{mode}: a stopped writer stays stopped', not daemon.state('upgrade-stopped-application')['running'],
              daemon.state('upgrade-stopped-application'))
        check(f'{mode}: an interrupted one-shot actor does not rerun', not daemon.state('upgrade-in-flight-actor')['running']
              and daemon.state('upgrade-in-flight-actor')['restarts'] == 0, daemon.state('upgrade-in-flight-actor'))
        check(f'{mode}: a finished migration actor does not rerun', not daemon.state('upgrade-migration-actor')['running']
              and daemon.state('upgrade-migration-actor')['restarts'] == 0, daemon.state('upgrade-migration-actor'))
        time.sleep(5)
        # Recorded, not assumed: whether the daemon autostarts a never-started container does not change the verdict.
        checks[f'{mode}: created but never started (observation)'] = {'passed': True, 'observed': daemon.state('upgrade-created-application')}
        check(f'{mode}: no restart or autostart reached a guarded program', not started())
    # A manual start of each state, with the gate still closed: the operator mistake the runbook forbids.
    daemon.docker('start', 'upgrade-stopped-application')
    daemon.docker('start', 'upgrade-created-application')
    daemon.docker('start', 'upgrade-migration-actor', check=False)
    time.sleep(4)
    check('manual start behind the closed gate reaches nothing', not started())
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, help='New file for the sanitized report')
    args = parser.parse_args()
    if args.evidence and args.evidence.exists():
        parser.error('The evidence file must be new.')
    daemon = Daemon()
    try:
        daemon.start()
        checks = {**proof(daemon), **upgrade_fence_proof(daemon)}
    finally:
        daemon.remove()
    report = {'kind': 'disposable-docker-restart-semantics', 'engine': DIND, 'vpsAccepted': False,
              'hostRebootExercised': False, 'checks': checks}
    if args.evidence:
        args.evidence.write_text(json.dumps(report, indent=2) + '\n')
    failed = [name for name, value in checks.items() if not value['passed']]
    print(json.dumps({'checks': len(checks), 'failed': failed}, indent=2))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
