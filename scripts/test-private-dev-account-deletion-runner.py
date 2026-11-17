#!/usr/bin/env python3
"""Private-dev runner fixture: real management/PostgreSQL, synthetic identity, no evidence config."""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
import pathlib
import runpy
import signal
import sys

sys.dont_write_bytecode = True
root = pathlib.Path(__file__).resolve().parents[1]
with contextlib.redirect_stdout(io.StringIO()):
    legacy = runpy.run_path(str(root / 'scripts/test-personal-data-ledger.py'))
case = legacy['Case']()
sql_bridge = r'''
if command == 'delete-ratings':
    import importlib.machinery, importlib.util
    load=importlib.machinery.SourceFileLoader('pd_dev_sql', os.environ['PD_TEST_REPOSITORY']+'/deploy/private-dev/bin/personal-data')
    spec=importlib.util.spec_from_loader(load.name,load);pd=importlib.util.module_from_spec(spec);load.exec_module(pd)
    pd.APPLICATION_DATABASE=os.environ['PD_TEST_DATABASE']
    runtime=pd.Runtime.__new__(pd.Runtime);runtime.postgres_container=os.environ['PD_TEST_POSTGRES']
    uid=values['--user-id']
    runtime.delete_ratings(uid);runtime.delete_preferences(uid)
'''
host = legacy['FAKE_HOST'].replace('account = state["accounts"].get(kc)', sql_bridge + '\naccount = state["accounts"].get(kc)')
case.host.write_text(host)
case.fault.write_text('delete-identity:before')
config = {'policy': 'private-dev', 'issuer': legacy['ISSUER'],
          'host_command': [sys.executable, '-I', str(case.host)],
          'management_url': os.environ['PD_TEST_MANAGEMENT_URL'],
          'credential_file': os.environ['PD_TEST_CREDENTIAL']}
path = case.owner / 'runner.json'
path.write_text(json.dumps(config)); path.chmod(0o600)
os.environ.update(FAKE_STATE=str(case.state), FAKE_FAULT=str(case.fault))
loader = importlib.machinery.SourceFileLoader('dev_runner', str(root / 'deploy/private-dev/bin/personal-data-self-service-runner'))
spec = importlib.util.spec_from_loader(loader.name, loader)
module = importlib.util.module_from_spec(spec); loader.exec_module(module)
runner = module.Runner(path)
runner.preflight()
runner.exchange({'action': 'heartbeat'})
print('READY', flush=True)
signal.signal(signal.SIGTERM, lambda *_: runner.stopped.set())
runner.run()
assert not case.ledger.exists() and not case.copy.exists()
