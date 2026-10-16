#!/usr/bin/env python3

"""Focused tests for the private-dev Keycloak smoke-account ownership rules."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import json
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

    def find_users_by_email(self, email):
        return [user for user in self.users if user.get("email") == email]

    def create_user(self, username):
        self.created = True
        self.users.append(
            {
                "attributes": {module.MARKER_ATTRIBUTE: ["true"]},
                "enabled": True,
                "id": "smoke-user-id",
                "username": username,
                **module.SYNTHETIC_PROFILE,
            }
        )

    def set_synthetic_profile(self, user_id):
        assert user_id == "smoke-user-id"
        self.users[0].update(module.SYNTHETIC_PROFILE)

    def direct_role_mappings(self, user_id):
        assert user_id == "smoke-user-id"
        return {"clientMappings": self.client_mappings, "realmMappings": []}

    def groups(self, user_id):
        assert user_id == "smoke-user-id"
        return self.user_groups

    def reset_password(self, user_id, password):
        self.reset = (user_id, password)


class FakeProfileAdmin(module.KeycloakAdmin):
    def __init__(self, profile):
        super().__init__("https://keycloak.invalid")
        self.profile = profile
        self.puts = 0

    def _request(self, method, path, *, body=None, form=None, expected, authenticated=True):
        assert form is None
        assert authenticated is True
        assert path == f"/admin/realms/{module.REALM}/users/profile"
        if method == "GET":
            return json.dumps(self.profile).encode("utf-8"), object()
        assert method == "PUT"
        assert expected == {200, 204}
        assert body is not None
        self.profile = body
        self.puts += 1
        return b"", object()


def smoke_user(*, marked=True, profile=None):
    attributes = {module.MARKER_ATTRIBUTE: ["true"]} if marked else {}
    user = {
        "attributes": attributes,
        "enabled": True,
        "id": "smoke-user-id",
        "username": "vgp-deployment-smoke",
        **module.SYNTHETIC_PROFILE,
    }
    user.update(profile or {})
    return user


created = FakeAdmin()
module.provision_smoke_user(created, "vgp-deployment-smoke", "correct-horse-battery-staple")
assert created.created is True
assert created.reset == ("smoke-user-id", "correct-horse-battery-staple")
assert (
    {field: created.users[0][field] for field in module.SYNTHETIC_PROFILE}
    == module.SYNTHETIC_PROFILE
)

existing = FakeAdmin([smoke_user()])
module.provision_smoke_user(existing, "vgp-deployment-smoke", "replacement-password-value")
assert existing.created is False
assert existing.reset == ("smoke-user-id", "replacement-password-value")

legacy = FakeAdmin([smoke_user()])
for field in module.SYNTHETIC_PROFILE:
    legacy.users[0].pop(field)
module.provision_smoke_user(legacy, "vgp-deployment-smoke", "replacement-password-value")
assert (
    {field: legacy.users[0][field] for field in module.SYNTHETIC_PROFILE}
    == module.SYNTHETIC_PROFILE
)

# An account provisioned before email verification is completed, not refused.
unverified = FakeAdmin([smoke_user(profile={"emailVerified": False})])
module.provision_smoke_user(unverified, "vgp-deployment-smoke", "replacement-password-value")
assert unverified.users[0]["emailVerified"] is True
assert unverified.reset == ("smoke-user-id", "replacement-password-value")

# With email sign-in the account is found by its synthetic email, whatever username is stored.
email_sign_in = FakeAdmin([smoke_user()])
module.provision_smoke_user(
    email_sign_in, module.SYNTHETIC_EMAIL, "replacement-password-value", email_as_username=True
)
assert email_sign_in.created is False
assert email_sign_in.reset == ("smoke-user-id", "replacement-password-value")
stale_username = FakeAdmin([smoke_user()])
try:
    module.provision_smoke_user(
        stale_username, "vgp-deployment-smoke", "replacement-password-value", email_as_username=True
    )
except module.ProvisioningError as error:
    assert module.SYNTHETIC_EMAIL in str(error)
else:
    raise AssertionError("a username-based smoke identifier was accepted with email sign-in")
assert stale_username.reset is None and stale_username.created is False

profile = FakeProfileAdmin({"attributes": [{"name": "username"}]})
profile.ensure_smoke_marker_profile()
assert profile.puts == 1
assert profile.profile["attributes"][-1] == module.MARKER_PROFILE_ATTRIBUTE
profile.ensure_smoke_marker_profile()
assert profile.puts == 1

for unsafe_profile in (
    FakeProfileAdmin({"attributes": [], "unmanagedAttributePolicy": "ENABLED"}),
    FakeProfileAdmin({"attributes": [{"name": module.MARKER_ATTRIBUTE}]}),
):
    try:
        unsafe_profile.ensure_smoke_marker_profile()
    except module.ProvisioningError:
        pass
    else:
        raise AssertionError("unsafe marker profile was accepted")

for unsafe in (
    FakeAdmin([smoke_user(marked=False)]),
    FakeAdmin([smoke_user(profile={"email": "someone@example.invalid"})]),
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
