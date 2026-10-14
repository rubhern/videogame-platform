#!/usr/bin/env python3

"""Prove the existing-realm recovery mail rollout against a real Keycloak realm."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import os
import pathlib
import subprocess
import sys

sys.dont_write_bytecode = True

repository_root = pathlib.Path(__file__).resolve().parents[1]
script_path = repository_root / "deploy/private-dev/bin/configure-keycloak-mail"
loader = importlib.machinery.SourceFileLoader("keycloak_mail_configuration", str(script_path))
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


def unmanaged(realm: dict[str, object]) -> dict[str, object]:
    return {name: value for name, value in realm.items() if name not in module.MANAGED_SETTINGS}


admin = module.provisioning.KeycloakAdmin(origin)
admin.authenticate(admin_username, admin_password)
desired = module.desired_settings()

# A fresh import already carries the reviewed settings.
assert run("--check").returncode == 0
imported = module.realm_representation(admin)
assert module.differing_settings(imported, desired) == []

# Simulate a realm imported before password recovery mail existed.
admin._request(
    "PUT",
    f"/admin/realms/{module.REALM}",
    body={"emailTheme": "keycloak", "smtpServer": {}},
    expected={204},
)
stale = module.realm_representation(admin)
assert module.differing_settings(stale, desired) == ["emailTheme", "smtpServer"]
check = run("--check")
assert check.returncode == 1 and "emailTheme, smtpServer" in check.stderr, check.stderr

applied = run()
assert applied.returncode == 0, applied.stderr
assert "Updated realm videogame-platform: emailTheme, smtpServer" in applied.stdout, applied.stdout
assert admin_password not in applied.stdout + applied.stderr
aligned = module.realm_representation(admin)
assert module.differing_settings(aligned, desired) == []
# Only the managed keys changed; identity data, clients and other policy are untouched.
assert unmanaged(aligned) == unmanaged(stale)

repeated = run()
assert repeated.returncode == 0 and "nothing changed" in repeated.stdout, repeated.stdout
assert run("--check").returncode == 0

print("Existing-realm recovery mail configuration is idempotent, partial and verified against Keycloak.")
