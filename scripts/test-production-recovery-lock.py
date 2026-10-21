#!/usr/bin/env python3
"""Exercise runtime-scoped recovery serialization and the identity fence on a disposable Docker volume."""
import importlib.machinery
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import uuid

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
            run, other = str(uuid.uuid4()), str(uuid.uuid4())
            with first.lock(maintenance=True):
                core.require(first.gate('maintenance-enter', run) == b'entered', 'The identity fence was not written.')
                core.require(first.gate('maintenance-enter', other) == b'conflict', 'A second run took a held fence.')
                core.require(first.gate('maintenance-exit', other) == b'conflict', 'Another run released the fence.')
            # The fence outlives the invocation that wrote it, across owner journals.
            try:
                with second.lock():
                    raise AssertionError('A recovery actor entered a held identity fence')
            except core.Refused:
                pass
            # The edge's read-only view of the same volume sees the exact file it matches on.
            view = core.run(['docker', 'run', '--rm', '--label', f'vgp.production-test={project}', '--network', 'none',
                             '--read-only', '-v', volume + ':/run/recovery:ro', 'python:3.12.15-slim-bookworm@sha256:'
                             '34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258', 'python3', '-c',
                             'import json;print(json.load(open("/run/recovery/IDENTITY-MAINTENANCE.json"))["runId"])'])
            core.require(view.strip() == run.encode(), 'The edge cannot read the identity fence.')
            with second.lock(maintenance=True):
                core.require(second.gate('maintenance-exit', run) == b'released', 'The fencing run could not release it.')
                core.require(second.gate('maintenance-exit', run) == b'absent', 'A released fence remained.')
            with first.lock():
                core.require(first.gate('maintenance-check') == b'none', 'The fence survived its release.')
            print('Runtime lease excludes distinct owner journals; the identity fence excludes other actors until '
                  'its run releases it; release and cleanup passed.')
        finally:
            ids = core.run(['docker','ps','-aq','--filter',f'label=vgp.production-test={project}']).split()
            if ids:
                core.run(['docker','rm','-fv',*[x.decode() for x in ids]])
            subprocess.run(['docker','volume','rm',volume],capture_output=True,check=True)
            core.require(not core.run(['docker','ps','-aq','--filter',f'label=vgp.production-test={project}']).strip(), 'Lease test helpers remain.')


if __name__ == '__main__':
    main()
