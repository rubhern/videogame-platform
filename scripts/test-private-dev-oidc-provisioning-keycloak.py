#!/usr/bin/env python3

"""Prove private-dev smoke-user provisioning against a real Keycloak realm."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import os
import pathlib
import sys

sys.dont_write_bytecode = True

repository_root = pathlib.Path(__file__).resolve().parents[1]
script_path = repository_root / "deploy/private-dev/bin/provision-oidc-smoke-user"
loader = importlib.machinery.SourceFileLoader("private_dev_oidc_provisioning", str(script_path))
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
smoke_username = required_environment("KEYCLOAK_TEST_SMOKE_USERNAME")
smoke_password = required_environment("KEYCLOAK_TEST_SMOKE_PASSWORD")

admin = module.KeycloakAdmin(origin)
admin.authenticate(admin_username, admin_password)

profile = admin.user_profile()
assert profile.get("unmanagedAttributePolicy") is None
assert profile.get("attributes", []).count(module.MARKER_PROFILE_ATTRIBUTE) == 1
admin.ensure_smoke_marker_profile()

# The shared realm imports the production identity policy: email sign-in, email as username.
assert admin.email_as_username() is True
assert smoke_username == module.SYNTHETIC_EMAIL


def smoke_users() -> list[dict[str, object]]:
    return admin.find_users_by_email(module.SYNTHETIC_EMAIL)


assert smoke_users() == []
module.provision_smoke_user(admin, smoke_username, smoke_password, email_as_username=True)
created = smoke_users()
assert len(created) == 1
created_user = created[0]
created_id = created_user.get("id")
assert isinstance(created_id, str) and created_id
assert created_user.get("attributes", {}).get(module.MARKER_ATTRIBUTE) == ["true"]
assert created_user.get("enabled") is True
assert {field: created_user.get(field) for field in module.SYNTHETIC_PROFILE} == module.SYNTHETIC_PROFILE
assert not admin.direct_role_mappings(created_id).get("clientMappings")
assert admin.groups(created_id) == []

module.provision_smoke_user(admin, smoke_username, smoke_password, email_as_username=True)
reprovisioned = smoke_users()
assert len(reprovisioned) == 1
assert reprovisioned[0].get("id") == created_id
assert reprovisioned[0].get("attributes", {}).get(module.MARKER_ATTRIBUTE) == ["true"]
assert {field: reprovisioned[0].get(field) for field in module.SYNTHETIC_PROFILE} == module.SYNTHETIC_PROFILE

print("Real Keycloak deployment smoke-user provisioning validation passed.")

# The aligned profile declares no names, so the minimal synthetic account needs no fixture
# completion before the OIDC and password-reset journeys.
attributes = {attribute["name"] for attribute in profile["attributes"]}
assert not attributes & {"firstName", "lastName"}
assert smoke_users()[0].get("requiredActions") == []
