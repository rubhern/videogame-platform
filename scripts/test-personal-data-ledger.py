#!/usr/bin/env python3
"""Prove the deletion-ledger driver against a fake runtime: write-ahead, interruption at every
step boundary, idempotent retry, restore replay, refusals and the absence of personal data.

No Docker and no network. The fake host mirrors personal-data's step contract and ordering
guards; scripts/validate-personal-data.sh proves the real host command against real Keycloak,
PostgreSQL and the packaged BFF.
"""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import pathlib
import shlex
import subprocess
import sys
import tempfile
import uuid

repository_root = pathlib.Path(__file__).resolve().parents[1]
driver = repository_root / "deploy/private-dev/bin/personal-data-ledger"
ISSUER = "https://identity.example.test/realms/videogame-platform"
STEPS = ("revoke", "delete-ratings", "delete-identity", "verify")

FAKE_HOST = r'''
import hashlib, json, os, pathlib, sys, uuid
state_path = pathlib.Path(os.environ["FAKE_STATE"])
state = json.loads(state_path.read_text())
command, arguments = sys.argv[1], sys.argv[2:]
pairs = [argument for argument in arguments if argument != "--application-must-be-stopped"]
values = dict(zip(pairs[::2], pairs[1::2]))
kc = values["--keycloak-user-id"]
derived = str(uuid.UUID(bytes=hashlib.md5(f"{state['issuer']}\n{kc}".encode()).digest(), version=3))
fault = pathlib.Path(os.environ["FAKE_FAULT"])
spec = fault.read_text().strip() if fault.exists() else ""

def fail(message):
    print(f"personal-data failed: {message}", file=sys.stderr)
    raise SystemExit(1)

def save():
    state_path.write_text(json.dumps(state))

if spec == f"{command}:before":
    fault.unlink()
    fail("connection lost before the step")
account = state["accounts"].get(kc)
if command == "identify":
    print(json.dumps({"runtime": "fake", "issuer": state["issuer"], "keycloakUserId": kc, "userId": derived,
                      "account": "absent" if account is None else ("enabled" if account["enabled"] else "disabled")}))
    raise SystemExit(0)
if command == "export":
    print(json.dumps({"format": "gameometro-personal-data-export", "account": account,
                      "identitySessions": [], "ratings": state["ratings"].get(derived, [])}))
    raise SystemExit(0)
if values["--user-id"] != derived:
    fail("The recorded UserId does not match this runtime's issuer")
if "--application-must-be-stopped" in arguments and state["applicationRunning"]:
    fail("The application is running; replay only before the service reopens")
result = {"step": command, "runtime": "fake"}
if command == "revoke":
    if account is not None:
        account["enabled"] = False
    result["bffSessionsRevoked"] = state["bffSessions"].pop(derived, 0) if state["applicationRunning"] else None
elif command == "delete-ratings":
    if account is not None and account["enabled"]:
        fail("The account is enabled; revoke it before deleting its ratings")
    result["deletedRatings"] = len(state["ratings"].pop(derived, []))
elif command == "delete-identity":
    if state["ratings"].get(derived):
        fail("The account still has ratings")
    result["identity"] = "deleted" if state["accounts"].pop(kc, None) is not None else "already-absent"
elif command == "verify":
    if kc in state["accounts"] or state["ratings"].get(derived) or state["bffSessions"].get(derived):
        fail("Erasure not verified")
    result["verified"] = True
save()
if spec == f"{command}:after":
    fault.unlink()
    fail("connection lost after the step ran")
print(json.dumps(result))
'''


def user_id(subject: str, issuer: str = ISSUER) -> str:
    return str(uuid.UUID(bytes=hashlib.md5(f"{issuer}\n{subject}".encode()).digest(), version=3))


TARGET = "11111111-1111-4111-8111-111111111111"
OTHER = "22222222-2222-4222-8222-222222222222"
REREGISTERED = "33333333-3333-4333-8333-333333333333"


def initial_state() -> dict[str, object]:
    return {
        "issuer": ISSUER,
        "applicationRunning": True,
        "accounts": {
            TARGET: {"enabled": True, "email": "target@example.test"},
            OTHER: {"enabled": True, "email": "other@example.test"},
        },
        "ratings": {
            user_id(TARGET): [{"gameId": "g1", "value": 7}, {"gameId": "g2", "value": 3}],
            user_id(OTHER): [{"gameId": "g1", "value": 9}],
        },
        "bffSessions": {user_id(TARGET): 2, user_id(OTHER): 1},
    }


class Case:
    def __init__(self) -> None:
        self.directory = pathlib.Path(tempfile.mkdtemp(prefix="vgp-ledger-test-"))
        self.directory.chmod(0o700)
        self.owner = self.directory / "owner"
        self.owner.mkdir(mode=0o700)
        self.ledger = self.owner / "deletion-ledger.jsonl"
        # Stands for storage independent of the workstation, such as an owner-held drive.
        self.independent = self.directory / "independent"
        self.independent.mkdir(mode=0o700)
        self.copy = self.independent / "deletion-ledger.jsonl"
        self.state = self.directory / "state.json"
        self.fault = self.directory / "fault"
        self.host = self.directory / "fake-host.py"
        self.host.write_text(FAKE_HOST)
        self.write_state(initial_state())

    def write_state(self, state: dict[str, object]) -> None:
        self.state.write_text(json.dumps(state))

    def read_state(self) -> dict[str, object]:
        return json.loads(self.state.read_text())

    def run(self, *arguments: str, fault: str = "", expect: int = 0, copy: bool = True) -> subprocess.CompletedProcess[str]:
        if fault:
            self.fault.write_text(fault)
        command = [sys.executable, "-I", str(driver), "--ledger", str(self.ledger),
                   *(["--ledger-copy", str(self.copy)] if copy else []),
                   "--host-command", f"{shlex.quote(sys.executable)} -I {shlex.quote(str(self.host))}", *arguments]
        result = subprocess.run(command, capture_output=True, text=True, check=False,
                                env={**os.environ, "FAKE_STATE": str(self.state), "FAKE_FAULT": str(self.fault)})
        assert result.returncode == expect, (arguments, result.returncode, result.stdout, result.stderr)
        return result

    def events(self) -> list[dict[str, object]]:
        return [json.loads(line) for line in self.ledger.read_text().splitlines()]


def assert_target_erased(state: dict[str, object]) -> None:
    assert TARGET not in state["accounts"], state
    assert user_id(TARGET) not in state["ratings"], state
    assert user_id(TARGET) not in state["bffSessions"], state


def assert_other_intact(state: dict[str, object]) -> None:
    assert state["accounts"][OTHER]["enabled"] is True, state
    assert state["ratings"][user_id(OTHER)] == [{"gameId": "g1", "value": 9}], state
    assert state["bffSessions"][user_id(OTHER)] == 1, state


def erase(case: Case, **options: object) -> subprocess.CompletedProcess[str]:
    return case.run("erase", "--request", "REQ-2026-0001", "--keycloak-user-id", TARGET, **options)


# 1. Complete erasure: write-ahead request, every step recorded, other account intact.
case = Case()
result = erase(case)
events = case.events()
assert [event["event"] for event in events] == ["requested", "step", "step", "step", "step", "completed"], events
assert [event.get("step") for event in events[1:5]] == list(STEPS), events
assert events[0]["userId"] == user_id(TARGET) and events[0]["request"] == "REQ-2026-0001"
assert events[1]["detail"]["bffSessionsRevoked"] == 2, events[1]
assert events[2]["detail"]["deletedRatings"] == 2, events[2]
assert_target_erased(case.read_state())
assert_other_intact(case.read_state())
assert case.ledger.stat().st_mode & 0o777 == 0o600, oct(case.ledger.stat().st_mode)
ledger_text = case.ledger.read_text()
for personal in ("target@example.test", "other@example.test", '"value"'):
    assert personal not in ledger_text, personal
# A repeated request for a completed erasure changes nothing.
before = case.ledger.read_bytes()
assert "nothing changed" in erase(case).stdout
assert case.ledger.read_bytes() == before
assert "No incomplete erasure" in case.run("retry").stdout

# 2. Interruption at every step boundary, before and after the step's own mutation, then retry.
for step in STEPS:
    for moment in ("before", "after"):
        case = Case()
        erase(case, fault=f"{step}:{moment}", expect=1)
        events = case.events()
        assert events[0]["event"] == "requested", events
        assert events[-1] == {**events[-1], "event": "step", "step": step, "outcome": "failed"}, events
        assert not any(event["event"] == "completed" for event in events), events
        state = case.read_state()
        assert_other_intact(state)
        if step != "revoke" or moment == "after":
            # Fail closed: once revocation has run, the account stays disabled.
            assert TARGET not in state["accounts"] or state["accounts"][TARGET]["enabled"] is False, state
        status = case.run("status").stdout
        assert "INCOMPLETE" in status and f"{step}=failed" in status, status
        # A new request for the same account must not start a second entry.
        assert "use retry" in erase(case, expect=1).stderr
        case.run("retry")
        assert case.events()[-1]["event"] == "completed"
        assert_target_erased(case.read_state())
        assert_other_intact(case.read_state())
        assert "INCOMPLETE" not in case.run("status").stdout

# 3. Restore of a backup taken before the erasure, then replay before reopening.
case = Case()
backup = case.read_state()
erase(case)
digest = hashlib.sha256(case.ledger.read_bytes()).hexdigest()
restored = json.loads(json.dumps(backup))
restored["bffSessions"] = {}
# An address freed by the erasure is registered again: a new subject the ledger never names.
restored["accounts"][REREGISTERED] = {"enabled": True, "email": "target@example.test"}
restored["ratings"][user_id(REREGISTERED)] = [{"gameId": "g3", "value": 5}]
case.write_state(restored)
receipts = case.directory / "receipts"
receipts.mkdir(mode=0o700)
refused = case.run("replay", "--receipt", str(receipts / "refused.json"), expect=1)
assert "application is running" in refused.stderr, refused.stderr
assert not (receipts / "refused.json").exists()
restored["applicationRunning"] = False
case.write_state(restored)
case.run("replay", "--receipt", str(receipts / "replay.json"))
state = case.read_state()
assert_target_erased(state)
assert state["accounts"][OTHER]["enabled"] is True and state["ratings"][user_id(OTHER)]
assert state["accounts"][REREGISTERED]["enabled"] is True and state["ratings"][user_id(REREGISTERED)]
receipt = json.loads((receipts / "replay.json").read_text())
assert receipt["ledgerSha256"] == digest and receipt["entries"] == receipt["verified"] == 1, receipt
assert (receipts / "replay.json").stat().st_mode & 0o777 == 0o600
# Replay never writes the ledger, and running it again is harmless.
assert hashlib.sha256(case.ledger.read_bytes()).hexdigest() == digest
case.run("replay", "--receipt", str(receipts / "replay-again.json"))

# 4. A target whose issuer differs derives other UserIds: replay stops before any mutation.
moved = case.read_state()
moved["issuer"] = "https://other.example.test/realms/videogame-platform"
moved["accounts"][TARGET] = {"enabled": True, "email": "target@example.test"}
case.write_state(moved)
assert "issuer changed" in case.run("replay", "--receipt", str(receipts / "moved.json"), expect=1).stderr
assert case.read_state()["accounts"][TARGET]["enabled"] is True

# 5. Refusals that protect the ledger and the data.
case = Case()
assert "opaque reference" in case.run("erase", "--request", "someone@example.test",
                                       "--keycloak-user-id", TARGET, expect=1).stderr
assert "canonical UUID" in case.run("erase", "--request", "REQ-1", "--keycloak-user-id",
                                    "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", expect=1).stderr
assert not case.ledger.exists()
assert "nothing recorded" in case.run("erase", "--request", "REQ-0002", "--keycloak-user-id",
                                      "44444444-4444-4444-8444-444444444444", expect=1).stderr
assert case.ledger.read_text() == ""
case.owner.chmod(0o755)
assert "private to its owner" in erase(case, expect=1).stderr
case.owner.chmod(0o700)
case.ledger.chmod(0o644)
assert "private to its owner" in erase(case, expect=1).stderr
case.ledger.chmod(0o600)
case.ledger.write_text('{"v":1,"event":"requested"')
assert "incomplete line" in case.run("status", expect=1).stderr
case.ledger.write_text("")
with open(case.ledger, "a", encoding="utf-8") as holder:
    fcntl.flock(holder, fcntl.LOCK_EX)
    assert "Another run holds the ledger" in erase(case, expect=1).stderr
assert case.read_state() == initial_state()

# 6. Export: a private new file, never overwritten, and no ledger entry.
case = Case()
export_path = case.owner / "export.json"
case.run("export", "--request", "REQ-0003", "--keycloak-user-id", TARGET, "--output", str(export_path))
assert export_path.stat().st_mode & 0o777 == 0o600
assert json.loads(export_path.read_text())["account"]["email"] == "target@example.test"
assert not case.ledger.exists()
case.run("export", "--request", "REQ-0003", "--keycloak-user-id", TARGET, "--output", str(export_path), expect=1)

# 7. The record is durable on two independent storages before any change, or nothing starts.
case = Case()
assert "requires --ledger-copy" in erase(case, copy=False, expect=2).stderr
assert "requires --ledger-copy" in case.run("retry", copy=False, expect=2).stderr
assert not case.ledger.exists() and case.read_state() == initial_state()
case.copy.write_text("")
case.copy.chmod(0o600)
case.independent.rename(case.directory / "unmounted")
assert "not available" in erase(case, expect=1).stderr
assert not case.ledger.exists() and case.read_state() == initial_state(), "nothing may start without the copy"
(case.directory / "unmounted").rename(case.independent)
case.copy.chmod(0o400)  # Storage that refuses writes.
erase(case, expect=1)
assert case.read_state() == initial_state() and case.ledger.read_text() == ""
case.copy.chmod(0o600)
case.ledger.write_text('{"at":"t1","event":"requested","keycloakUserId":"a","request":"R-A","userId":"u","v":1}\n')
case.copy.write_text('{"at":"t2","event":"requested","keycloakUserId":"b","request":"R-B","userId":"u","v":1}\n')
assert "diverge" in erase(case, expect=1).stderr
assert case.read_state() == initial_state()
case.ledger.write_text("")
case.copy.write_text("")
erase(case)
assert case.copy.read_bytes() == case.ledger.read_bytes() and case.ledger.read_text().count("\n") == 6
# An append interrupted between the copies leaves one a prefix of the other: the next run completes it.
complete = case.ledger.read_text()
case.copy.write_text(complete.splitlines(keepends=True)[0])
assert "Brought the ledger copy up to date" in case.run("status").stderr
assert case.copy.read_text() == complete
# A lost working copy is rebuilt from the independent one, and vice versa.
case.ledger.unlink()
assert "complete" in case.run("status").stdout and case.ledger.read_text() == complete
assert case.ledger.stat().st_mode & 0o777 == 0o600
case.copy.unlink()
case.run("retry")
assert case.copy.read_text() == complete
case.copy = case.ledger
assert "separate file" in case.run("status", expect=1).stderr

print("Deletion-ledger driver proven: write-ahead, interruption before and after every step, "
      "idempotent retry, restore replay with refusals, issuer guard, no personal data in the ledger, and "
      "no change before the record is durable in two independent copies.")
