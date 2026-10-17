#!/usr/bin/env python3
"""Focused synthetic privacy lifecycle: real GPG/files, existing fake-runtime erasure contract.

No production adapter, timer, DNS, host or real account is used. This includes the
existing ledger regression and exercises new orchestration at its administrative seam.
"""
import copy
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
legacy = runpy.run_path(str(ROOT / "scripts/test-personal-data-ledger.py"))
Case, TARGET, OTHER = (legacy[key] for key in ("Case", "TARGET", "OTHER"))
keydir = Path(tempfile.mkdtemp(prefix="vgp-synthetic-gpg-")); keydir.chmod(0o700)
# This WSL clock has documented backward steps. Only the disposable synthetic keyring
# pins GPG time; production never ignores key/signature clock validity.
(keydir / "gpg.conf").write_text("faked-system-time 1704067200!\n")
(keydir / "gpg.conf").chmod(0o600)
subprocess.run(["gpg", "--homedir", str(keydir), "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
                "--quick-generate-key", "Synthetic privacy test <privacy@example.test>", "ed25519", "sign", "0"],
               check=True, capture_output=True)
listing = subprocess.check_output(["gpg", "--homedir", str(keydir), "--with-colons", "--list-secret-keys"], stderr=subprocess.DEVNULL).decode()
fingerprint = next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))
subprocess.run(["gpg", "--homedir", str(keydir), "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
                "--quick-add-key", fingerprint, "cv25519", "encr", "0"], check=True, capture_output=True)
# Negative control: moving before key creation must still refuse encryption.
negative = subprocess.run(["gpg", "--homedir", str(keydir), "--batch", "--faked-system-time", "1704067100!",
                           "--trust-model", "always", "--recipient", fingerprint, "--encrypt"],
                          input=b"synthetic clock control", capture_output=True)
assert negative.returncode != 0 and b"future" in negative.stderr
spec = importlib.util.spec_from_file_location("privacy_archive_test", ROOT / "deploy/private-dev/lib/personal_data_archive.py")
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
current = dt.datetime(2030, 1, 1, tzinfo=dt.timezone.utc)


def protected(parent, name):
    directory = parent / name; directory.mkdir(mode=0o700); return directory


def expect_failure(action):
    try:
        action()
    except (a.ArchiveError, OSError, ValueError):
        return
    raise AssertionError("Expected safe refusal")


base = Path(tempfile.mkdtemp(prefix="vgp-legal-source-test-")); base.chmod(0o700)
primary, independent = protected(base, "archive"), protected(base, "copy")
archive = a.Archive(primary, independent, keydir, fingerprint, fingerprint, clock=lambda: current)
entry = {"request": "REQ-SYNTH-1", "keycloakUserId": TARGET, "userId": legacy["user_id"](TARGET),
         "archiveProfile": "pending-signup-v1"}
source = {"issuer": legacy["ISSUER"], "userId": entry["userId"], "ratingCount": 0,
          "account": {"keycloakUserId": TARGET, "email": "synthetic@example.test", "createdTimestamp": 1,
                      "emailVerified": False, "eligibleHuman": True, "enabled": False,
                      "password": "must-not-survive", "attributes": {"private": "must-not-survive"}}}
# Creating a case fsyncs its parent in both stores, before a sealed deletion can proceed.
real_fsync=a.os.fsync; synced_roots=[]
def observed_fsync(descriptor):
    identity=a.os.fstat(descriptor).st_ino
    if identity in (primary.stat().st_ino, independent.stat().st_ino): synced_roots.append(identity)
    return real_fsync(descriptor)
a.os.fsync=observed_fsync
archive.dirs("REQ-DIRECTORY-DURABILITY")
a.os.fsync=real_fsync
assert set(synced_roots)=={primary.stat().st_ino,independent.stat().st_ino}
fifo=base/"not-a-file";os.mkfifo(fifo,0o600)
expect_failure(lambda: a.private(fifo))
expect_failure(lambda: a.parse_time("secret-must-not-be-echoed"))

manifest = archive.seal(entry, source)
assert manifest["expiresAt"] == "2033-01-01T00:00:00Z" and manifest["reviewAt"] == "2031-01-01T00:00:00Z"
original = (primary / entry["request"] / "source.gpg").read_bytes()
assert b"synthetic@example.test" not in original
plaintext = archive.gpg("--decrypt", data=original)
assert b"must-not-survive" not in plaintext and b"ratings" not in plaintext and b"password" not in plaintext
assert set(json.loads(plaintext)) == {"format", "version", "reference", "profile", "issuer", "subject", "userId",
                                    "email", "createdTimestamp", "emailVerified", "blockedAt", "expiresAt", "reviewAt"}
assert archive.seal(entry) == manifest and (primary / entry["request"] / "source.gpg").read_bytes() == original
assert (independent / entry["request"] / "source.gpg").read_bytes() == original
expect_failure(lambda: a.Archive(primary, primary, keydir, fingerprint, fingerprint))
expect_failure(lambda: a.Archive(primary, independent, keydir, fingerprint, fingerprint))  # concurrent owner operation
linked = base / "symlink"; linked.symlink_to(primary, target_is_directory=True)
expect_failure(lambda: a.Archive(linked, independent, keydir, fingerprint, fingerprint))
(primary / entry["request"] / "source.gpg").write_bytes(b"tampered")
expect_failure(lambda: archive.verify(entry["request"]))
(primary / entry["request"] / "source.gpg").write_bytes(original)
(primary / entry["request"] / "source.gpg").chmod(0o644)
expect_failure(lambda: archive.verify(entry["request"]))
(primary / entry["request"] / "source.gpg").chmod(0o600)
# Interrupt after first ciphertext: no destructive source write, recover the exact first capture.
staged = {**entry, "request": "REQ-STAGED-1"}
real_write = a.write
calls = 0

def interrupted(path, value, replace=False):
    global calls
    calls += 1
    if calls == 2:
        raise OSError("synthetic independent-storage outage")
    return real_write(path, value, replace)

a.write = interrupted
expect_failure(lambda: archive.seal(staged, source))
a.write = real_write
archive.seal(staged)
assert archive.verify(staged["request"])["profile"] == "pending-signup-v1"
# A crash while staging a new hold must not allow purge under the old no-hold review.
pending_review={**entry,"request":"REQ-PENDING-REVIEW"}
archive.seal(pending_review,source)
current=dt.datetime(2033,1,1,tzinfo=dt.timezone.utc)
archive.review(pending_review["request"])
staging=primary/pending_review["request"]/"review.json.pending"
a.write(staging,a.encoded({"holdReason":"CASE-NEW-HOLD"}))
expect_failure(lambda: archive.purge(pending_review["request"],True))
assert (primary/pending_review["request"]/"source.gpg").exists()
staging.unlink()  # Only the synthetic test reconciles the injected unfinished review.
# Dangling review paths must never masquerade as the absence of a hold/review.
saved_review=(primary/pending_review["request"]/"review.json").read_bytes()
review_path=primary/pending_review["request"]/"review.json"
review_path.unlink();review_path.symlink_to(base/"missing-private-review")
expect_failure(lambda: archive.purge(pending_review["request"],True))
review_path.unlink();a.write(review_path,saved_review)
current=dt.datetime(2030,1,1,tzinfo=dt.timezone.utc)
# Annual review, justified extension, no early/held destruction; then both-copy purge and replay tombstone.
expect_failure(lambda: archive.purge(entry["request"], True))
current = dt.datetime(2033, 1, 1, tzinfo=dt.timezone.utc)
expect_failure(lambda: archive.purge(entry["request"], True))  # overdue annual review
archive.review(entry["request"], hold_reason="CASE-SYNTH-1", extend_until="2035-01-01T00:00:00Z")
expect_failure(lambda: archive.purge(entry["request"], True))
archive.review(entry["request"], release_hold=True)
expect_failure(lambda: archive.purge(entry["request"], True))  # extension still applies
current = dt.datetime(2035, 1, 1, tzinfo=dt.timezone.utc)
archive.review(entry["request"])
assert archive.purge(entry["request"])["action"] == "would-destroy"
assert (primary / entry["request"] / "source.gpg").exists()
assert archive.purge(entry["request"], True)["action"] == "destroyed"
for root in (primary, independent):
    assert not (root / entry["request"] / "source.gpg").exists()
    assert (root / entry["request"] / "destroyed.json").exists()
# A restored legal payload cannot be resealed or reused; destruction proof causes re-purge.
a.write(primary / entry["request"] / "source.gpg", original)
expect_failure(lambda: archive.seal(entry, source))
archive.purge(entry["request"], True)
assert not (primary / entry["request"] / "source.gpg").exists()
# Interrupted write-ahead destruction reconciles signed intent to both copies before any unlink.
archive.review(staged["request"])
calls = 0

def fail_destruction_copy(path, value, replace=False):
    global calls
    if path.name.startswith("destroyed"):
        calls += 1
        if calls == 3:
            raise OSError("synthetic destruction-copy outage")
    return real_write(path, value, replace)

a.write = fail_destruction_copy
expect_failure(lambda: archive.purge(staged["request"], True))
a.write = real_write
assert (primary / staged["request"] / "source.gpg").exists()
assert archive.purge(staged["request"], True)["action"] == "destroyed"
assert a.years(dt.datetime(2032,2,29,tzinfo=dt.timezone.utc),1).day == 28
# Corrupt signatures never authenticate a ciphertext hash.
signature_path=primary/staged["request"]/"destroyed.json.sig"
signature_bytes=signature_path.read_bytes();signature_path.write_bytes(b"invalid signature")
expect_failure(lambda: archive.purge(staged["request"],True))
signature_path.write_bytes(signature_bytes)

# Ordinary verified profile excludes ratings; particular case excludes unrelated rows.
verified = copy.deepcopy(source); verified["account"]["emailVerified"] = True; verified["ratingCount"] = 20
requested = {**entry, "request": "REQ-REQUESTED", "archiveProfile": "requested-account-v1"}
value = a.profile_payload(requested, verified, current)
assert "ratings" not in value
expect_failure(lambda: a.profile_payload(entry, verified, current))
case_entry = {**entry, "archiveProfile": "case-hold-v1"}
game = "99999999-9999-4999-8999-999999999999"
row = {"gameId": game, "value": 7, "createdAt": "t1", "updatedAt": "t2"}
hold = {"caseReference": "CASE-1", "reason": "particular synthetic claim", "gameIds": [game]}
assert a.profile_payload(case_entry, verified, current, hold, [row])["ratings"] == [row]
expect_failure(lambda: a.profile_payload(case_entry, verified, current, hold, [{**row, "gameId": TARGET}]))

# Reuse legacy fake-host steps; only new read operations/maintenance are synthetic adapters.
WRAPPER = r'''
import json, os, pathlib, sys
state_path = pathlib.Path(os.environ["FAKE_STATE"])
state = json.loads(state_path.read_text())
command = sys.argv[1]
values = dict(zip(sys.argv[2::2], sys.argv[3::2]))
if command == "cleanup-candidates":
    cutoff, maximum = int(values["--cutoff-ms"]), int(values["--max"])
    users = [{"keycloakUserId": key, "createdTimestamp": value["createdTimestamp"], "emailVerified": False,
              "eligibleHuman": True} for key,value in sorted(state["accounts"].items())
             if value.get("emailVerified") is False and value["createdTimestamp"] <= cutoff]
    print(json.dumps({"issuer": state["issuer"], "candidates": users[:maximum]}))
    if os.environ.get("VERIFY_AFTER_PROBE"):
        for user in state["accounts"].values(): user["emailVerified"]=True
        state_path.write_text(json.dumps(state))
    sys.exit(0)
if command == "archive-snapshot":
    subject = values["--keycloak-user-id"]
    import uuid, hashlib
    uid = str(uuid.UUID(bytes=hashlib.md5(f"{state['issuer']}\n{subject}".encode()).digest(),version=3))
    user = state["accounts"].get(subject)
    print(json.dumps({"issuer":state["issuer"], "userId":uid,
                     "account":None if user is None else {**user,"keycloakUserId":subject,"eligibleHuman":True},
                     "ratingCount":len(state["ratings"].get(uid,[]))})); sys.exit(0)
if command in ("enter","check","exit"):
    path=pathlib.Path(os.environ["FAKE_STATE"]+".maintenance")
    with path.open("a") as output: output.write(command+"\n")
    if command=="enter" and os.environ.get("VERIFY_DURING_PROBE"):
        for user in state["accounts"].values(): user["emailVerified"]=True
        state_path.write_text(json.dumps(state))
    print(json.dumps({"issuer": state["issuer"],"runId":values["--run-id"],
                      "state":"wrong" if os.environ.get("BAD_DRAIN") and command!="exit" else
                              ("released" if command=="exit" else "drained")}));sys.exit(0)
'''


def fixture():
    test = Case()
    test.host.write_text(WRAPPER + "\n" + legacy["FAKE_HOST"])
    state = test.read_state(); state["issuer"] = "https://identity.example.test/realms/gameometro"
    stamp = int(dt.datetime.now(dt.timezone.utc).timestamp()*1000)
    state["accounts"][TARGET].update(createdTimestamp=stamp-40*86400000,emailVerified=False)
    state["accounts"][OTHER].update(createdTimestamp=stamp-100*86400000,emailVerified=True)
    state["ratings"] = {}; state["bffSessions"] = {}
    test.write_state(state)
    return test


def run(test, *options, expect=0, extra=None, fault=""):
    if fault: test.fault.write_text(fault)
    roots=[protected(test.directory,"archive"),protected(test.directory,"archive-copy")] if not (test.directory/"archive").exists() else [test.directory/"archive",test.directory/"archive-copy"]
    proof=test.directory/"proof"
    if not proof.exists(): proof.mkdir(mode=0o700)
    holds=test.directory/"holds"
    if not holds.exists(): holds.mkdir(mode=0o700)
    command=[sys.executable,"-I",str(legacy["driver"]),"--ledger",str(test.ledger),"--ledger-copy",str(test.copy),
             "--host-command",f"{sys.executable} -I {test.host}","--archive-root",str(roots[0]),"--archive-copy",str(roots[1]),
             "--proof-root",str(proof),"--holds",str(holds),"--gnupg-home",str(keydir),"--recipient",fingerprint,"--signer",fingerprint,*options]
    env={**os.environ,"FAKE_STATE":str(test.state),"FAKE_FAULT":str(test.fault),**(extra or {})}
    result=subprocess.run(command,capture_output=True,text=True,env=env,timeout=90)
    assert result.returncode==expect,(options,result.stdout,result.stderr)
    return result


# Empty probe/dry-run/missing-drain and verification race must never delete a verified account.
test=fixture(); before=test.read_state()
run(test,"cleanup-unverified")
assert test.read_state()==before and not Path(str(test.state)+".maintenance").exists()
run(test,"cleanup-unverified","--execute",expect=1)
assert test.read_state()==before
adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,extra={"BAD_DRAIN":"1"},expect=1)
assert test.read_state()==before
assert Path(str(test.state)+".maintenance").read_text().splitlines()==["enter","exit"]
test=fixture(); adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,extra={"VERIFY_DURING_PROBE":"1"})
assert TARGET in test.read_state()["accounts"] and test.read_state()["accounts"][TARGET]["emailVerified"] is True
assert not test.ledger.read_text()
test=fixture(); state=test.read_state();state["accounts"][TARGET]["emailVerified"]=True;test.write_state(state)
run(test,"cleanup-unverified","--execute")
assert not Path(str(test.state)+".maintenance").exists() and TARGET in test.read_state()["accounts"]
# Candidates disappearing during key/storage preflight do not trigger maintenance.
test=fixture();adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,extra={"VERIFY_AFTER_PROBE":"1"})
assert not Path(str(test.state)+".maintenance").exists()
assert TARGET in test.read_state()["accounts"] and not test.ledger.read_text()

# Complete pending erasure, two-copy archive, repeat, and original replay protects against resurrection.
test=fixture(); adapter=f"{sys.executable} -I {test.host}"; saved=test.read_state()
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter)
assert TARGET not in test.read_state()["accounts"] and OTHER in test.read_state()["accounts"]
assert "email" not in test.ledger.read_text() and "synthetic@example.test" not in test.ledger.read_text()
reference=test.events()[0]["request"]
run(test,"archive-verify","--request",reference)
proof=json.loads((test.directory/"proof"/(reference+".json")).read_text())
assert proof["verifiedAbsent"] and "email" not in proof and "subject" not in proof and "ratings" not in proof
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter)
saved["applicationRunning"]=False;test.write_state(saved)
receipt=test.owner/"replay.json"
run(test,"replay","--receipt",str(receipt))
assert TARGET not in test.read_state()["accounts"] and OTHER in test.read_state()["accounts"]
# Interruption after ratings deletion retries with the original sealed source; no recapture/retention reset.
test=fixture(); adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,fault="delete-ratings:after",expect=1)
assert test.read_state()["accounts"][TARGET]["enabled"] is False
reference=test.events()[0]["request"]
archive_file=test.directory/"archive"/reference/"source.gpg"; original=archive_file.read_bytes()
before=test.read_state()
run(test,"retry",expect=1)  # Cleanup recovery requires the same deny/drain contract.
assert test.read_state()==before
run(test,"retry","--maintenance-command",adapter)
assert archive_file.read_bytes()==original and TARGET not in test.read_state()["accounts"]
# A newly verified account after an interrupted pre-revoke cleanup is never disabled/deleted on retry.
test=fixture();adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,fault="revoke:before",expect=1)
state=test.read_state();state["accounts"][TARGET]["emailVerified"]=True;test.write_state(state);before=test.read_state()
run(test,"retry","--maintenance-command",adapter,expect=1)
assert test.read_state()==before and TARGET in before["accounts"] and before["accounts"][TARGET]["enabled"]
assert Path(str(test.state)+".maintenance").read_text().splitlines()[-3:]==["enter","check","exit"]
# A restore must not turn that refused incomplete cleanup into verified-account deletion.
state=test.read_state();state["applicationRunning"]=False;test.write_state(state);before=test.read_state()
receipt=test.owner/"refused-cleanup-replay.json"
run(test,"replay","--receipt",str(receipt),expect=1)
assert test.read_state()==before and not receipt.exists()
# A concrete hold added after sealing stops recovery before further deletion.
test=fixture();adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,fault="delete-ratings:before",expect=1)
(test.directory/"holds"/(TARGET+".json")).write_text(json.dumps({"caseReference":"CASE-NEW","reason":"new particular claim","gameIds":[]}))
(test.directory/"holds"/(TARGET+".json")).chmod(0o600)
before=test.read_state();run(test,"retry","--maintenance-command",adapter,expect=1)
assert test.read_state()==before and TARGET in before["accounts"]
# Replay without a first pre-delete archive refuses instead of consuming the last source.
test=fixture();adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,fault="revoke:after",expect=1)
state=test.read_state();state["applicationRunning"]=False;test.write_state(state);before=test.read_state()
run(test,"replay","--receipt",str(test.owner/"missing-archive-replay.json"),expect=1)
assert test.read_state()==before and TARGET in before["accounts"]
# A sealed incomplete cleanup also refuses replay when the restored account is now verified.
test=fixture();adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,fault="delete-ratings:before",expect=1)
state=test.read_state();state["applicationRunning"]=False;state["accounts"][TARGET]["emailVerified"]=True;test.write_state(state);before=test.read_state()
run(test,"replay","--receipt",str(test.owner/"verified-archive-replay.json"),expect=1)
assert test.read_state()==before

# Recovery also succeeds after the identity was already removed, reusing the first capture.
test=fixture();adapter=f"{sys.executable} -I {test.host}"
run(test,"cleanup-unverified","--execute","--maintenance-command",adapter,fault="delete-identity:after",expect=1)
assert TARGET not in test.read_state()["accounts"]
state=test.read_state();state["applicationRunning"]=False;test.write_state(state)
reference=test.events()[0]["request"]
original=(test.directory/"archive"/reference/"source.gpg").read_bytes()
run(test,"replay","--receipt",str(test.owner/"sealed-incomplete-replay.json"))
assert (test.directory/"archive"/reference/"source.gpg").read_bytes()==original
run(test,"retry","--maintenance-command",adapter)
assert test.events()[-1]["event"]=="completed"
# Raw host diagnostics must never enter stderr, stdout or either ledger copy.
test=fixture();test.host.write_text(WRAPPER+"\nif command=='delete-ratings':\n print('synthetic@example.test bearer-secret',file=sys.stderr);sys.exit(1)\n"+legacy["FAKE_HOST"])
result=run(test,"erase","--request","REQ-NO-LEAK","--keycloak-user-id",TARGET,expect=1)
assert "synthetic@example.test" not in result.stderr+result.stdout+test.ledger.read_text()+test.copy.read_text()
assert "bearer-secret" not in result.stderr+result.stdout

# A zero exit code is not an erasure-verification receipt.
test=fixture();test.host.write_text(WRAPPER+"\nif command=='verify':\n print(json.dumps({'verified':False}));sys.exit(0)\n"+legacy["FAKE_HOST"])
run(test,"erase","--request","REQ-NO-FALSE-COMPLETION","--keycloak-user-id",TARGET,expect=1)
assert not any(event["event"]=="completed" for event in test.events())

# The ordinary verified-request profile is selected automatically; source ratings are excluded.
test=fixture(); state=test.read_state(); state["accounts"][TARGET]["emailVerified"]=True; test.write_state(state)
run(test,"erase","--request","REQ-DEFAULT-ORDINARY","--keycloak-user-id",TARGET)
run(test,"archive-verify","--request","REQ-DEFAULT-ORDINARY")
assert test.events()[0]["archiveProfile"]=="requested-account-v1"
# An owner-verified rights request for a never-verified account also selects its minimum profile.
test=fixture()
run(test,"erase","--request","REQ-DEFAULT-PENDING","--keycloak-user-id",TARGET)
run(test,"archive-verify","--request","REQ-DEFAULT-PENDING")
assert test.events()[0]["archiveProfile"]=="pending-signup-v1"
# Invalid metadata cannot echo source text; review output carries dates/state only.
result=run(test,"archive-review","--request","REQ-DEFAULT-PENDING","--extend-until","synthetic@example.test bearer-secret",expect=1)
assert "synthetic@example.test" not in result.stdout+result.stderr and "bearer-secret" not in result.stderr
result=run(test,"archive-review","--request","REQ-DEFAULT-PENDING","--hold-reason","CASE-OPAQUE-1")
assert "CASE-OPAQUE-1" not in result.stdout and json.loads(result.stdout)["held"] is True
print("Privacy lifecycle passed: real-GPG allowlists/sealing/tamper/retention/holds/purge/restored-archive refusal; "
      "conditional preflight, guarded cleanup retry/new verification/late holds/absent identity, "
      "redacted diagnostics, explicit verification, existing erasure retry and backup replay with synthetic runtime.")
subprocess.run(["gpgconf","--homedir",str(keydir),"--kill","gpg-agent"],check=False,capture_output=True)
