#!/usr/bin/env python3

"""Prove the existing-realm catalogue operator rollout against a real Keycloak realm."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True

repository_root = pathlib.Path(__file__).resolve().parents[1]
script_path = repository_root / "deploy/private-dev/bin/configure-keycloak-operator"
loader = importlib.machinery.SourceFileLoader("keycloak_operator_configuration", str(script_path))
spec = importlib.util.spec_from_loader(loader.name, loader)
assert spec is not None
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)


def required_environment(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise AssertionError(f"Missing required integration-test environment variable: {name}")
    return value


origin = required_environment("KEYCLOAK_TEST_ORIGIN")
admin_username = required_environment("KEYCLOAK_TEST_ADMIN_USERNAME")
admin_password = required_environment("KEYCLOAK_TEST_ADMIN_PASSWORD")


def run(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(script_path),
            "--origin",
            origin,
            "--admin-username",
            admin_username,
            "--admin-password-file",
            "/dev/stdin",
            *arguments,
        ],
        input=admin_password,
        capture_output=True,
        check=False,
        text=True,
    )


admin = module.provisioning.KeycloakAdmin(origin)
admin.authenticate(admin_username, admin_password)
client = module.Client(admin)
role, mapper = module.desired()


def unmanaged() -> dict[str, object]:
    representation = client.get(client.path)
    assert isinstance(representation, dict)
    return {name: value for name, value in representation.items() if name != "protocolMappers"}


# A fresh import of the local realm already declares exactly the role and mapper.
assert run("--check").returncode == 0
assert module.drift(client, role, mapper) == []

# Simulate a realm imported before #247: no role, no mapper.
before = unmanaged()
admin._request("DELETE", f"{module.ADMIN_REALM}{client.path}/roles/{module.ROLE}", expected={204})
current_mapper = client.mapper(mapper["name"])
assert current_mapper is not None
admin._request(
    "DELETE", f"{module.ADMIN_REALM}{client.path}/protocol-mappers/models/{current_mapper['id']}", expected={204}
)
check = run("--check")
assert check.returncode == 1 and "role, mapper" in check.stderr, check.stderr
refused = run("--grant", "anyone")
assert refused.returncode == 1 and "not aligned" in refused.stderr, refused.stderr

applied = run()
assert applied.returncode == 0, applied.stderr
assert "Updated realm videogame-platform: role, mapper" in applied.stdout, applied.stdout
assert admin_password not in applied.stdout + applied.stderr
assert module.drift(client, role, mapper) == []
assert unmanaged() == before, "only the role and the mapper changed"
repeated = run()
assert repeated.returncode == 0 and "nothing changed" in repeated.stdout, repeated.stdout

# Grant and revoke change exactly one named account.
username = f"operator-{uuid.uuid4()}"
admin._request(
    "POST",
    f"{module.ADMIN_REALM}/users",
    body={"username": username, "email": f"{username}@example.test", "emailVerified": True, "enabled": True},
    expected={201},
)
granted = run("--grant", username)
assert granted.returncode == 0 and "next sign-in" in granted.stdout, granted.stdout + granted.stderr
assert username in run("--list-holders").stdout.splitlines()
assert "nothing changed" in run("--grant", username).stdout
revoked = run("--revoke", username)
assert revoked.returncode == 0 and "Revoked" in revoked.stdout, revoked.stdout + revoked.stderr
assert username not in run("--list-holders").stdout.splitlines()
missing = run("--grant", f"absent-{uuid.uuid4()}")
assert missing.returncode == 1 and "No single account" in missing.stderr, missing.stderr
account = client.account(username)
admin._request("DELETE", f"{module.ADMIN_REALM}/users/{account['id']}", expected={204})

print(json.dumps({"catalogueOperatorRole": "aligned, idempotent, grant/revoke verified"}))
print("Existing-realm catalogue operator configuration is additive, idempotent and verified against Keycloak.")
