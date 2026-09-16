#!/usr/bin/env python3

"""Focused tests for the private-dev Keycloak smoke-account ownership rules."""

from __future__ import annotations

import importlib.machinery
import importlib.util
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


class FakeAdmin:
    def __init__(self, users=None, *, client_mappings=None, groups=None):
        self.users = list(users or [])
        self.client_mappings = client_mappings
        self.user_groups = list(groups or [])
        self.created = False
        self.reset = None

    def find_users(self, username):
        return [user for user in self.users if user["username"] == username]

    def create_user(self, username):
        self.created = True
        self.users.append(
            {
                "attributes": {module.MARKER_ATTRIBUTE: ["true"]},
                "enabled": True,
                "id": "smoke-user-id",
                "username": username,
            }
        )

    def direct_role_mappings(self, user_id):
        assert user_id == "smoke-user-id"
        return {"clientMappings": self.client_mappings, "realmMappings": []}

    def groups(self, user_id):
        assert user_id == "smoke-user-id"
        return self.user_groups

    def reset_password(self, user_id, password):
        self.reset = (user_id, password)


def smoke_user(*, marked=True):
    attributes = {module.MARKER_ATTRIBUTE: ["true"]} if marked else {}
    return {
        "attributes": attributes,
        "enabled": True,
        "id": "smoke-user-id",
        "username": "vgp-deployment-smoke",
    }


created = FakeAdmin()
module.provision_smoke_user(created, "vgp-deployment-smoke", "correct-horse-battery-staple")
assert created.created is True
assert created.reset == ("smoke-user-id", "correct-horse-battery-staple")

existing = FakeAdmin([smoke_user()])
module.provision_smoke_user(existing, "vgp-deployment-smoke", "replacement-password-value")
assert existing.created is False
assert existing.reset == ("smoke-user-id", "replacement-password-value")

for unsafe in (
    FakeAdmin([smoke_user(marked=False)]),
    FakeAdmin([smoke_user()], client_mappings={"realm-management": {}}),
    FakeAdmin([smoke_user()], groups=[{"id": "operators"}]),
):
    try:
        module.provision_smoke_user(
            unsafe, "vgp-deployment-smoke", "correct-horse-battery-staple"
        )
    except module.ProvisioningError:
        pass
    else:
        raise AssertionError("unsafe existing smoke account was accepted")
    assert unsafe.reset is None

print("Private-dev OIDC smoke-user provisioning validation passed.")
