#!/usr/bin/env python3
"""Restart-policy semantics on a real dockerd inside a disposable, unpublished container.

Proves the Docker behaviour the post-acceptance transition relies on: daemon restart and abrupt
daemon loss, automatic restart after a crash, intentional stops, one-shot actors and the closed
recovery gate. It starts no Gameómetro service and is not VPS or host-reboot acceptance. It needs
a privileged local container, so it is invoked by hand rather than by CI.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

import install_private_production as install

ROOT = Path(__file__).resolve().parents[1]
DIND = 'docker:29.8.2-dind'
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
             '-e', 'DOCKER_TLS_CERTDIR=', '--label', 'vgp.restart-proof=true', DIND,
             'dockerd', '--host', 'unix:///var/run/docker.sock'])
        self.wait()
        image = subprocess.run(['docker', 'save', install.HELPER], capture_output=True, check=True, timeout=300).stdout
        loaded = run(['docker', 'exec', '-i', self.name, 'docker', 'load'], data=image, timeout=300)
        self.image = loaded.splitlines()[-1].split()[-1]

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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, help='New file for the sanitized report')
    args = parser.parse_args()
    if args.evidence and args.evidence.exists():
        parser.error('The evidence file must be new.')
    daemon = Daemon()
    try:
        daemon.start()
        checks = proof(daemon)
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
