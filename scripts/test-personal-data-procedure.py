#!/usr/bin/env python3
"""Exercise the personal-data procedure against real Keycloak, PostgreSQL and the packaged BFF.

Started by scripts/validate-personal-data.sh, which owns the disposable topology and passes its
names in PD_* variables. Synthetic accounts only. The scenario: real BFF sessions and a rating
written through the product API, an export, an erasure interrupted after the ratings are gone,
its retry, the restore of a backup taken before the erasure, the replay refused while the
application runs and verified while it is stopped, and a re-registered address left untouched.
"""

from __future__ import annotations

import json
import os
import re
import pathlib
import secrets
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

repository_root = pathlib.Path(__file__).resolve().parents[1]
ledger_driver = repository_root / "deploy/private-dev/bin/personal-data-ledger"
host_tool = repository_root / "deploy/private-dev/bin/personal-data"
REALM = "videogame-platform"

origin = os.environ["PD_KEYCLOAK_ORIGIN"]
admin_username = os.environ["PD_ADMIN_USERNAME"]
admin_password = pathlib.Path(os.environ["PD_ADMIN_PASSWORD_FILE"]).read_text().strip()
issuer = os.environ["PD_ISSUER"]
postgres = os.environ["PD_POSTGRES"]
application = os.environ["PD_APPLICATION"]
keycloak = os.environ["PD_KEYCLOAK"]
network = os.environ["PD_NETWORK"]
playwright_image = os.environ["PD_PLAYWRIGHT_IMAGE"]
work = pathlib.Path(os.environ["PD_WORK"])

owner = work / "owner"
owner.mkdir(mode=0o700)
sessions = work / "sessions"
sessions.mkdir(mode=0o700)
ledger = owner / "deletion-ledger.jsonl"
independent = work / "independent"
independent.mkdir(mode=0o700)
ledger_copy = independent / "deletion-ledger.jsonl"
host_command = shlex.join([
    sys.executable, "-I", str(host_tool),
    "--origin", origin, "--admin-username", admin_username,
    "--admin-password-file", os.environ["PD_ADMIN_PASSWORD_FILE"], "--issuer", issuer,
    "--postgres-container", postgres, "--application-container", application,
])
accounts = {
    "target": {"username": "pd-target", "email": "pd-target@example.test", "password": secrets.token_hex(16)},
    "other": {"username": "pd-other", "email": "pd-other@example.test", "password": secrets.token_hex(16)},
}


def run(command: list[str], *, stdin: bytes | None = None, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(command, input=stdin, capture_output=True, check=False, timeout=300)
    if check and result.returncode != 0:
        raise SystemExit(f"{command[:3]} failed: {result.stderr.decode(errors='replace')[-800:]}")
    return result


def sql(database: str, statement: str) -> str:
    return run(["docker", "exec", postgres, "psql", "-U", "postgres", "-d", database, "-tAc", statement]).stdout.decode().strip()


def ratings_of(user_id: str) -> int:
    return int(sql("videogame_platform", f"SELECT count(*) FROM ratings.rating WHERE user_id = '{user_id}'"))


PS5 = "10000000-0000-4000-8000-000000000001"


def prefer_ps5(user_id: str) -> None:
    # Preferred platforms (#295), stored as the product API stores them for this UserId.
    sql("videogame_platform",
        f"INSERT INTO catalogue.platform_preference_choice (user_id, updated_at) VALUES ('{user_id}', now());"
        f"INSERT INTO catalogue.platform_preference (user_id, platform_id) VALUES ('{user_id}', '{PS5}')")


def preferences_of(user_id: str) -> int:
    return int(sql("videogame_platform",
                   f"SELECT count(*) FROM catalogue.platform_preference_choice WHERE user_id = '{user_id}'"))


class Admin:
    def __init__(self) -> None:
        self.token = ""

    def authenticate(self) -> None:
        form = urllib.parse.urlencode({"client_id": "admin-cli", "grant_type": "password",
                                       "username": admin_username, "password": admin_password}).encode()
        with urllib.request.urlopen(f"{origin}/realms/master/protocol/openid-connect/token", form, timeout=20) as response:
            self.token = json.loads(response.read())["access_token"]

    def request(self, method: str, path: str, body: object = None) -> tuple[int, object, str]:
        self.authenticate()
        request = urllib.request.Request(
            f"{origin}/admin/realms/{REALM}{path}", method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = response.read()
                return response.status, (json.loads(payload) if payload else None), response.headers.get("Location", "")
        except urllib.error.HTTPError as error:
            return error.code, None, ""

    def create(self, username: str, email: str, password: str) -> str:
        status, _, location = self.request("POST", "/users", {
            "username": username, "email": email, "emailVerified": True, "enabled": True,
            "firstName": "Synthetic", "lastName": "Account",
            "credentials": [{"type": "password", "value": password, "temporary": False}],
        })
        assert status == 201, status
        return location.rsplit("/", 1)[1]

    def account(self, keycloak_user_id: str) -> dict[str, object] | None:
        status, body, _ = self.request("GET", f"/users/{keycloak_user_id}")
        return body if status == 200 else None


def ledger_run(*arguments: str, command: str = host_command, expect: int = 0) -> subprocess.CompletedProcess[bytes]:
    result = run([sys.executable, "-I", str(ledger_driver), "--ledger", str(ledger), "--ledger-copy", str(ledger_copy),
                  "--host-command", command, *arguments],
                 check=False)
    assert result.returncode == expect, (arguments, result.stdout.decode(), result.stderr.decode())
    return result


def sessions_helper(*arguments: str) -> str:
    environment = []
    for account, values in accounts.items():
        environment += ["--env", f"{account.upper()}_USERNAME={values['username']}",
                        "--env", f"{account.upper()}_PASSWORD={values['password']}"]
    return run([
        "docker", "run", "--rm", "--network", network, "--user", f"{os.getuid()}:{os.getgid()}",
        "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
        "--env", "SESSION_STATE_DIRECTORY=/state", *environment,
        "--volume", f"{repository_root}:/work:ro", "--volume", f"{sessions}:/state", "--workdir", "/work",
        playwright_image, "python3", "scripts/test-personal-data-sessions.py", *arguments,
    ]).stdout.decode()


def devices_authenticated() -> dict[str, bool]:
    return json.loads(sessions_helper("check", "target-phone", "target-laptop", "other"))


def wait_for(description: str, probe) -> None:
    for _ in range(180):
        if probe():
            return
        time.sleep(1)
    raise SystemExit(f"{description} did not become ready")


def keycloak_ready() -> bool:
    try:
        with urllib.request.urlopen(f"{origin}/realms/{REALM}", timeout=5) as response:
            return response.status == 200
    except (urllib.error.URLError, OSError):
        return False


def application_ready() -> bool:
    return run(["docker", "exec", application, "wget", "-q", "-O", "/dev/null",
                "http://127.0.0.1:8081/actuator/health/readiness"], check=False).returncode == 0


admin = Admin()
for values in accounts.values():
    values["id"] = admin.create(values["username"], values["email"], values["password"])
target_id, other_id = accounts["target"]["id"], accounts["other"]["id"]

# Native candidate filters are exercised only in the disposable CI realm. No cleanup runs.
pending_id = admin.create("pd-pending-filter", "pd-pending-filter@example.test", secrets.token_hex(16))
status, _, _ = admin.request("PUT", f"/users/{pending_id}", {"emailVerified": False})
assert status == 204
created = admin.account(pending_id)["createdTimestamp"]
def candidates(cutoff: int) -> list[dict]:
    result = json.loads(run(shlex.split(host_command) + ["cleanup-candidates", "--cutoff-ms", str(cutoff), "--max", "100"]).stdout)
    assert result["issuer"] == issuer and len(result["candidates"]) <= 100
    return result["candidates"]
assert pending_id not in [item["keycloakUserId"] for item in candidates(created - 1)]
assert pending_id in [item["keycloakUserId"] for item in candidates(created + 1)]
assert not {target_id, other_id} & {item["keycloakUserId"] for item in candidates(created + 1)}
# Effective management roles exclude an otherwise ordinary pending human account.
status, management_clients, _ = admin.request("GET", "/clients?clientId=realm-management&max=2")
assert status == 200 and len(management_clients) == 1
management_id = management_clients[0]["id"]
status, management_role, _ = admin.request("GET", f"/clients/{management_id}/roles/view-users")
assert status == 200
assert admin.request("POST", f"/users/{pending_id}/role-mappings/clients/{management_id}", [management_role])[0] == 204
pending_source = json.loads(run(shlex.split(host_command) + ["archive-snapshot", "--keycloak-user-id", pending_id]).stdout)
assert pending_source["account"]["eligibleHuman"] is False
assert admin.request("DELETE", f"/users/{pending_id}")[0] == 204

# Two devices of the target account and one of the other, each with a real BFF session; one
# rating per account written through the product API, so the stored UserId is the BFF's own.
sessions_helper("login", "target-phone:TARGET_USERNAME:TARGET_PASSWORD:7",
                "target-laptop:TARGET_USERNAME:TARGET_PASSWORD", "other:OTHER_USERNAME:OTHER_PASSWORD:9")
assert devices_authenticated() == {"other": True, "target-laptop": True, "target-phone": True}
identity = json.loads(run(shlex.split(host_command) + ["identify", "--keycloak-user-id", target_id]).stdout)
other_identity = json.loads(run(shlex.split(host_command) + ["identify", "--keycloak-user-id", other_id]).stdout)
target_user, other_user = identity["userId"], other_identity["userId"]
assert ratings_of(target_user) == 1 and ratings_of(other_user) == 1, "the derived UserId differs from the BFF's"
prefer_ps5(target_user)
prefer_ps5(other_user)

snapshot = json.loads(run(shlex.split(host_command) + ["archive-snapshot", "--keycloak-user-id", target_id]).stdout)
assert snapshot["ratingCount"] == 1 and snapshot["userId"] == target_user
assert set(snapshot["account"]) == {"keycloakUserId", "email", "createdTimestamp", "emailVerified", "enabled", "eligibleHuman"}
scoped = json.loads(run(shlex.split(host_command) + ["archive-ratings", "--keycloak-user-id", target_id,
    "--game-ids", "30000000-0000-4000-8000-000000000005"]).stdout)
assert scoped["issuer"] == issuer
assert len(scoped["ratings"]) == 1 and scoped["ratings"][0]["value"] == 7
assert set(scoped["ratings"][0]) == {"gameId", "value", "createdAt", "updatedAt"}

# The real host command refuses out-of-order and mismatched steps before changing anything.
def refused(*arguments: str) -> str:
    result = run(shlex.split(host_command) + list(arguments), check=False)
    assert result.returncode == 1 and not result.stdout, (arguments, result.stdout)
    return result.stderr.decode()


assert "revoke it before" in refused("delete-ratings", "--keycloak-user-id", other_id, "--user-id", other_user)
assert "still has ratings" in refused("delete-identity", "--keycloak-user-id", other_id, "--user-id", other_user)
assert "does not match" in refused("revoke", "--keycloak-user-id", other_id, "--user-id", target_user)
assert "canonical UUID" in refused("revoke", "--keycloak-user-id", other_id.upper(), "--user-id", other_user)
assert "application is running" in refused("revoke", "--keycloak-user-id", other_id, "--user-id", other_user,
                                           "--application-must-be-stopped")
assert admin.account(other_id)["enabled"] is True and ratings_of(other_user) == 1
assert devices_authenticated() == {"other": True, "target-laptop": True, "target-phone": True}

# A backup taken before the erasure, as the nightly pair would be.
dumps = {}
for database in ("videogame_platform", "videogame_keycloak"):
    dumps[database] = run(["docker", "exec", postgres, "pg_dump", "-U", "postgres", "--format=custom", database]).stdout

# Export: the holder's profile, sessions and ratings only, in a private file; no ledger entry.
export_path = owner / "export-REQ-EXPORT-1.json"
ledger_run("export", "--request", "REQ-EXPORT-1", "--keycloak-user-id", target_id, "--output", str(export_path))
document = json.loads(export_path.read_text())
assert export_path.stat().st_mode & 0o777 == 0o600
assert document["account"]["email"] == accounts["target"]["email"] and document["productUserId"] == target_user
assert [(rating["gameId"], rating["value"]) for rating in document["ratings"]] == [("30000000-0000-4000-8000-000000000005", 7)]
assert document["ratings"][0]["title"], document["ratings"][0]
assert document["preferredPlatforms"] == {"configured": True, "platforms": [{"platformId": PS5, "name": "PlayStation 5"}]}
assert len(document["identitySessions"]) == 2 and all(session["ipAddress"] for session in document["identitySessions"])
assert accounts["other"]["email"] not in export_path.read_text()
# Only allow-listed, non-secret fields leave Keycloak: no token, credential, secret, cookie or
# session identifier, whatever the Admin API returns for the account and its sessions.
assert all(set(session) == {"startedAt", "lastAccessAt", "ipAddress"} for session in document["identitySessions"])
assert set(document["account"]) <= {"keycloakUserId", "username", "email", "emailVerified", "enabled", "createdAt",
                                    "firstName", "lastName", "attributes"}, document["account"]


def keys(value: object) -> list[str]:
    if isinstance(value, dict):
        return [*value, *(key for item in value.values() for key in keys(item))]
    if isinstance(value, list):
        return [key for item in value for key in keys(item)]
    return []


assert not [key for key in keys(document) if any(word in key.lower() for word in
            ("token", "secret", "password", "credential", "hash", "cookie", "session_id"))], keys(document)
export_text = export_path.read_text()
_, live_sessions, _ = admin.request("GET", f"/users/{target_id}/sessions")
assert len(live_sessions) == 2 and set(live_sessions[0]) >= {"id", "ipAddress", "start", "lastAccess"}, live_sessions
# Field names only, as evidence of what the Admin API offers and the export leaves out.
print("Keycloak session fields:", ", ".join(sorted(live_sessions[0])))
print("Keycloak account fields:", ", ".join(sorted(admin.account(target_id))))
secrets_that_must_not_leak = [session["id"] for session in live_sessions] + [
    accounts["target"]["password"], admin_password, os.environ["PD_BFF_CLIENT_SECRET"],
]
# Every browser cookie of both hosts: the BFF session and Keycloak's SSO and sign-in cookies.
cookies = {}
for cookie_file in sessions.glob("*.cookies"):
    for line in cookie_file.read_text().splitlines():
        if line.startswith("Set-Cookie3: "):
            name, value = line[len("Set-Cookie3: "):].split(";", 1)[0].split("=", 1)
            cookies.setdefault(name, []).append(value.strip('"'))
assert "vgp_session" in cookies and "KEYCLOAK_IDENTITY" in cookies, sorted(cookies)
secrets_that_must_not_leak += [value for values in cookies.values() for value in values]
for value in secrets_that_must_not_leak:
    if len(value) >= 8:
        assert value not in export_text, "a secret, cookie or session identifier reached the export"
assert not re.search(r"eyJ[A-Za-z0-9_-]{10,}", export_text), "a JWT-shaped value reached the export"
assert not ledger.exists()

# Erasure interrupted after the ratings are deleted: the connection is lost before the identity step.
fail_once = work / "fail-once.py"
fail_once.write_text(
    "import os, pathlib, sys\n"
    "marker, step, command = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3:]\n"
    "if marker.exists() and step in command:\n"
    "    marker.unlink()\n"
    "    sys.exit('personal-data failed: connection lost')\n"
    "os.execvp(command[0], command)\n"
)
marker = work / "fail-marker"
marker.touch()
interrupted = shlex.join([sys.executable, "-I", str(fail_once), str(marker), "delete-identity"]) + " " + host_command
ledger_run("erase", "--request", "REQ-ERASE-1", "--keycloak-user-id", target_id, command=interrupted, expect=1)
status = ledger_run("status").stdout.decode()
assert "INCOMPLETE" in status and "revoke=done" in status and "delete-ratings=done" in status, status
assert "delete-identity=failed" in status, status
events = [json.loads(line) for line in ledger.read_text().splitlines()]
assert events[0]["event"] == "requested" and events[1]["detail"]["bffSessionsRevoked"] == 2, events[:2]
assert admin.account(target_id)["enabled"] is False, "fail closed: the account stays disabled"
assert ratings_of(target_user) == 0 and ratings_of(other_user) == 1
assert devices_authenticated() == {"other": True, "target-laptop": False, "target-phone": False}

ledger_run("retry")
assert "INCOMPLETE" not in ledger_run("status").stdout.decode()
assert admin.account(target_id) is None and ratings_of(target_user) == 0
assert admin.account(other_id)["enabled"] is True and ratings_of(other_user) == 1
assert preferences_of(target_user) == 0 and preferences_of(other_user) == 1
ledger_text = ledger.read_text()
assert accounts["target"]["email"] not in ledger_text and accounts["target"]["username"] not in ledger_text

# Restore the pre-erasure backup: the deleted account and its rating come back.
run(["docker", "stop", application])
run(["docker", "stop", keycloak])
for database, dump in dumps.items():
    run(["docker", "exec", "-i", postgres, "pg_restore", "-U", "postgres", "--clean", "--if-exists",
         f"--dbname={database}"], stdin=dump)
run(["docker", "start", keycloak])
wait_for("Restored Keycloak", keycloak_ready)
assert admin.account(target_id) is not None and ratings_of(target_user) == 1, "the backup predates the erasure"
assert preferences_of(target_user) == 1, "the backup predates the erasure"

# The replay refuses an open service, then reapplies and verifies every entry before reopening.
receipts = owner
run(["docker", "start", application])
wait_for("Application", application_ready)
refused = ledger_run("replay", "--receipt", str(receipts / "replay-refused.json"), expect=1)
# The ledger deliberately hides raw host diagnostics; validate its public failure contract
# and the actual refusal, without restoring diagnostic leakage just to satisfy the test.
assert b"revoke failed (exit 1)" in refused.stderr, refused.stderr
assert b"application is running" not in refused.stderr, refused.stderr
assert admin.account(target_id)["enabled"] is True and ratings_of(target_user) == 1, "a refused replay changes nothing"
assert not (receipts / "replay-refused.json").exists(), "a refused replay cannot certify restoration"
run(["docker", "stop", application])
ledger_run("replay", "--receipt", str(receipts / "replay.json"))
receipt = json.loads((receipts / "replay.json").read_text())
assert receipt["entries"] == receipt["verified"] == 1 and receipt["outcome"] == "verified", receipt
assert admin.account(target_id) is None and ratings_of(target_user) == 0
assert preferences_of(target_user) == 0 and preferences_of(other_user) == 1
assert admin.account(other_id)["enabled"] is True and ratings_of(other_user) == 1

# The freed address registers again as a new subject, which a later replay leaves alone.
reregistered = admin.create("pd-target-again", accounts["target"]["email"], secrets.token_hex(16))
assert reregistered != target_id
ledger_run("replay", "--receipt", str(receipts / "replay-after-reregistration.json"))
assert admin.account(reregistered)["enabled"] is True

run(["docker", "start", application])
wait_for("Reopened application", application_ready)
for container in (application, keycloak):
    output = run(["docker", "logs", container])
    logs = output.stdout + output.stderr
    assert accounts["target"]["email"].encode() not in logs, f"{container} logged the erased address"

print("Personal-data procedure proven with real Keycloak, PostgreSQL and the BFF: export, interrupted "
      "erasure and retry, two-device revocation, restore of a pre-erasure backup, refused and verified "
      "replay, untouched re-registration and other account, no address in the ledger or logs.")
