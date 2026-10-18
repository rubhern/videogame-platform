#!/usr/bin/env python3
"""Focused administrative read seam: exact native filters and closed projections; no live runtime."""
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import sys
import urllib.parse

sys.dont_write_bytecode = True
root = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader("personal_data_read_test", str(root / "deploy/private-dev/bin/personal-data"))
spec = importlib.util.spec_from_loader(loader.name, loader)
m = importlib.util.module_from_spec(spec); loader.exec_module(m)
subject="11111111-1111-4111-8111-111111111111"
issuer="https://identity.example.test/realms/gameometro"


class FakeRuntime:
    def __init__(self):
        self.issuer=issuer
        self.request=None
    def keycloak(self, method, path):
        self.request=(method,path)
        return [{"id":subject,"email":"synthetic@example.test","createdTimestamp":100,
                 "emailVerified":False,"firstName":"must-not-escape","credentials":["secret"]}]
    def account(self, _subject):
        return {"email":"synthetic@example.test","createdTimestamp":100,"emailVerified":False,
                "enabled":False,"firstName":"must-not-escape","attributes":{"arbitrary":"secret"}}
    def rating_count(self, _user): return 0
    def eligible_human(self, _user): return True
    def sql(self, statement, user):
        self.statement,self.user=statement,user
        return '[]'


# Realm transport selection preserves isolated legacy CI while refusing unsafe production issuers.
class FakeAdmin:
    def __init__(self, _origin): pass
    def authenticate(self, *_arguments): pass
original_admin=m.provisioning.KeycloakAdmin
m.provisioning.KeycloakAdmin=FakeAdmin
try:
    for selected, realm in [(issuer,"gameometro"),("http://keycloak:8080/realms/videogame-platform","videogame-platform")]:
        instance=m.Runtime(origin="http://127.0.0.1",admin_username="synthetic",admin_password="synthetic",
                           issuer=selected,label="synthetic",postgres_container="synthetic",application_container=None)
        assert instance.realm==realm
    for selected in ["http://identity.example.test/realms/gameometro",issuer+"?other=1",issuer+"/", "https://x/realms/other"]:
        try: m.Runtime(origin="http://127.0.0.1",admin_username="synthetic",admin_password="synthetic",
                       issuer=selected,label="synthetic",postgres_container="synthetic",application_container=None)
        except m.ProvisioningError: pass
        else: raise AssertionError("Unsupported runtime issuer was accepted")
finally: m.provisioning.KeycloakAdmin=original_admin

# Effective administrative roles make an otherwise ordinary production user ineligible.
real=m.Runtime.__new__(m.Runtime);real.realm="gameometro";real.application_container="synthetic";real.postgres_container="synthetic"
management="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
role_queries=[]
def roles(method,path):
    role_queries.append(path)
    return [{"id":management,"clientId":"realm-management"}] if path.startswith("/clients?") else [{"name":"realm-admin"}]
real.keycloak=roles
assert not real.eligible_human({"id":subject})
assert role_queries==["/clients?clientId=realm-management&first=0&max=2",f"/users/{subject}/role-mappings/clients/{management}/composite"]
assert not real.eligible_human({"id":subject,"serviceAccountClientId":"synthetic"})
real.keycloak=lambda method,path: [{"id":management,"clientId":"realm-management"}] if path.startswith("/clients?") else []
assert real.eligible_human({"id":subject})
real.application_container=None
try: real.application_running()
except m.ProvisioningError: pass
else: raise AssertionError("Missing production application was treated as a proven stopped runtime")
real.application_container="synthetic"
original_run=m.subprocess.run
from types import SimpleNamespace
try:
    m.subprocess.run=lambda *_args,**_kwargs: SimpleNamespace(returncode=1,stdout="",stderr="private@example.test bearer-secret")
    for operation in (real.application_running,lambda:real.sql("SELECT 1",subject)):
        try: operation()
        except m.ProvisioningError as error:
            assert "private@example.test" not in str(error) and "bearer-secret" not in str(error)
        else: raise AssertionError("Unknown runtime/error state did not fail closed")
    m.subprocess.run=lambda *_args,**_kwargs: SimpleNamespace(returncode=0,stdout=json.dumps({"services":{"application":{"environment":{"OIDC_ISSUER_URI":issuer}}}}))
    try: m.compose_issuer([])
    except m.ProvisioningError: pass
    else: raise AssertionError("Private-dev selector accepted a production issuer")
finally: m.subprocess.run=original_run

runtime=FakeRuntime()
response=m.cleanup_candidates(runtime,200,1)
method,path=runtime.request
query=urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
assert method=="GET" and query=={"emailVerified":["false"],"createdBefore":["200"],"first":["0"],"max":["1"],"briefRepresentation":["true"]}
assert set(response["candidates"][0])=={"keycloakUserId","createdTimestamp","emailVerified","eligibleHuman"}
for maximum in (0,101,-1):
    try: m.cleanup_candidates(runtime,200,maximum)
    except m.ProvisioningError: pass
    else: raise AssertionError("Unbounded native query allowed")
snapshot=m.archive_snapshot(runtime,subject)
assert "attributes" not in json.dumps(snapshot) and "secret" not in json.dumps(snapshot)
assert snapshot["userId"]==m.derive_user_id(issuer,subject)
m.archive_rating_page(runtime,subject,subject,"00000000-0000-0000-0000-000000000000")
assert "ORDER BY game_id LIMIT 100" in runtime.statement and "game_id >" in runtime.statement
assert "r.user_id" not in runtime.statement and runtime.user==snapshot["userId"]
try: m.archive_rating_page(runtime,subject,"bad' OR true;--",subject)
except m.ProvisioningError: pass
else: raise AssertionError("Unvalidated GameId reached SQL")
print("Administrative read seam passed: server age/verification filter, page bounds, source allowlists, immutable identity and exceptional keyset parameters. Real PostgreSQL/Keycloak execution remains the disposable CI gate.")

# Streaming history must not materialize all historical accounts for an active lookup.
import tempfile, shutil, tracemalloc, uuid
loader=importlib.machinery.SourceFileLoader("privacy_ledger_stream_test",str(root/"deploy/private-dev/bin/personal-data-ledger"))
spec=importlib.util.spec_from_loader(loader.name,loader);driver=importlib.util.module_from_spec(spec);loader.exec_module(driver)
with tempfile.TemporaryDirectory(prefix="vgp-ledger-stream-") as temporary:
    parent=Path(temporary);parent.chmod(0o700)
    first=parent/"first";second=parent/"second";first.mkdir(mode=0o700);second.mkdir(mode=0o700)
    primary=first/"ledger";independent=second/"ledger"
    with primary.open("w") as output:
        for i in range(10000):
            subject=str(uuid.UUID(int=i+1))
            output.write(json.dumps({"v":1,"at":"t1","event":"requested","keycloakUserId":subject,"userId":subject,"request":"HIST-"+str(i)})+"\n")
            output.write(json.dumps({"v":1,"at":"t2","event":"completed","keycloakUserId":subject})+"\n")
    primary.chmod(0o600);shutil.copyfile(primary,independent);independent.chmod(0o600)
    tracemalloc.start()
    with driver.open_ledger(primary,independent) as ledger:
        assert ledger.entry(str(uuid.UUID(int=10000)))["request"]=="HIST-9999"
    _,peak=tracemalloc.get_traced_memory();tracemalloc.stop()
    assert peak<2*1024*1024,peak
    print(f"Streaming ledger passed: 10000 historical accounts, peak traced lookup/reconciliation memory {peak} bytes.")

# Time spent by replay performing separately bounded host steps is not ledger scan time.
clock=[0.0];original_clock=driver.time.monotonic
try:
    driver.time.monotonic=lambda:clock[0]
    sample=driver.Ledger.__new__(driver.Ledger)
    sample.events=lambda:iter([{"event":"requested","keycloakUserId":subject}]*2)
    sample.entry=lambda _subject:{"keycloakUserId":subject}
    entries=sample.entries();next(entries);clock[0]+=600;next(entries)
    try: next(entries)
    except StopIteration: pass
    else: raise AssertionError("Unexpected ledger entry")
finally: driver.time.monotonic=original_clock
print("Review guards passed: administrative-role exclusion, runtime/SQL fail-closed behavior, private realm isolation and scan-only deadline.")
