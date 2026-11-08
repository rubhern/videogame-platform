#!/usr/bin/env python3
"""Disposable real-management/real-PostgreSQL/real-GPG self-service fixture.

The identity-provider seam is synthetic. The Java test owns authenticated browser admission,
actual session fencing and PostgreSQL, while this process runs the unchanged #274 engine.
Two temporary ledger/archive paths test protocol durability, not physical independence.
"""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
import pathlib
import runpy
import subprocess
import sys

sys.dont_write_bytecode = True
root = pathlib.Path(__file__).resolve().parents[1]
with contextlib.redirect_stdout(io.StringIO()):
    scope = runpy.run_path(str(root / "scripts/test-personal-data-privacy.py"))
case = scope["fixture"]()
scope["run"](case, "status", expect=1)
state = case.read_state()
state["accounts"][scope["TARGET"]]["emailVerified"] = True
case.write_state(state)
# Let the existing operator adapter erase real PostgreSQL data using its existing bounded SQL.
sql_bridge = r'''
if command == 'delete-ratings':
    import importlib.machinery, importlib.util
    load=importlib.machinery.SourceFileLoader('pd_real_sql', os.environ['PD_TEST_REPOSITORY']+'/deploy/private-dev/bin/personal-data')
    spec=importlib.util.spec_from_loader(load.name,load);pd=importlib.util.module_from_spec(spec);load.exec_module(pd)
    pd.APPLICATION_DATABASE=os.environ['PD_TEST_DATABASE']
    runtime=pd.Runtime.__new__(pd.Runtime);runtime.postgres_container=os.environ['PD_TEST_POSTGRES']
    uid=values['--user-id']
    runtime.delete_ratings(uid);runtime.delete_preferences(uid)
'''
case.host.write_text(scope["WRAPPER"] + sql_bridge + scope["legacy"]["FAKE_HOST"])
case.fault.write_text("delete-identity:before")
config = {"issuer": state["issuer"], "ledger": str(case.ledger), "ledger_copy": str(case.copy),
    "proof_root": str(case.directory/"proof"), "archive_root": str(case.directory/"archive"),
    "archive_copy": str(case.directory/"archive-copy"), "gnupg_home": str(scope["keydir"]),
    "recipient": scope["fingerprint"], "signer": scope["fingerprint"], "holds": str(case.directory/"holds"),
    "host_command": [sys.executable, "-I", str(case.host)],
    "management_url": os.environ["PD_TEST_MANAGEMENT_URL"], "credential_file": os.environ["PD_TEST_CREDENTIAL"]}
path = case.owner/"runner.json";path.write_text(json.dumps(config));path.chmod(0o600)
os.environ.update(FAKE_STATE=str(case.state), FAKE_FAULT=str(case.fault))
load=importlib.machinery.SourceFileLoader("self_service_runner_fixture", str(root/"deploy/private-dev/bin/personal-data-self-service-runner"))
spec=importlib.util.spec_from_loader(load.name,load);module=importlib.util.module_from_spec(spec);load.exec_module(module)
runner = module.Runner(path)
runner.preflight()
runner.exchange({"action": "heartbeat"})
print("READY", flush=True)
# Remains automatic after the user command; the test can kill/restart this process.
try:
    runner.run()
finally:
    subprocess.run(["gpgconf","--homedir",str(scope["keydir"]),"--kill","gpg-agent"],capture_output=True)
