#!/usr/bin/env python3
"""Exercise runtime-scoped recovery serialization on a disposable Docker volume."""
import importlib.machinery
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('recovery_lease_test', str(ROOT / 'deploy/public-production/bin/recovery'))
spec = importlib.util.spec_from_loader(loader.name, loader)
core = importlib.util.module_from_spec(spec)
loader.exec_module(core)
project = 'vgp-production-test-lease-' + secrets.token_hex(6)
volume = project + '-recovery-state'


def actor(state):
    runtime = core.Runtime.__new__(core.Runtime)
    runtime.project, runtime.gate_volume, runtime.state = project, volume, state
    return runtime


def main():
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='vgp-recovery-lease-') as name:
        root = Path(name)
        (root / 'first').mkdir(mode=0o700); (root / 'second').mkdir(mode=0o700)
        first, second = actor(root / 'first'), actor(root / 'second')
        try:
            with first.lock():
                try:
                    with second.lock():
                        raise AssertionError('A second owner journal acquired the runtime lease')
                except core.Refused:
                    pass
                core.require(first.gate('check') == b'closed', 'A new runtime must start closed.')
            with second.lock():
                second.gate('close')
            print('Runtime lease excludes distinct owner journals; release and cleanup passed.')
        finally:
            ids = core.run(['docker','ps','-aq','--filter',f'label=vgp.production-test={project}']).split()
            if ids:
                core.run(['docker','rm','-fv',*[x.decode() for x in ids]])
            subprocess.run(['docker','volume','rm',volume],capture_output=True,check=True)
            core.require(not core.run(['docker','ps','-aq','--filter',f'label=vgp.production-test={project}']).strip(), 'Lease test helpers remain.')


if __name__ == '__main__':
    main()
