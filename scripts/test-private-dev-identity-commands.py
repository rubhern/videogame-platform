#!/usr/bin/env python3

"""Focused tests for the private-dev identity and relay inputs, without Keycloak."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import pathlib
import sys
import tempfile

sys.dont_write_bytecode = True

repository_root = pathlib.Path(__file__).resolve().parents[1]


def load(name: str, path: str) -> object:
    loader = importlib.machinery.SourceFileLoader(name, str(repository_root / path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


identity = load("private_dev_keycloak_identity", "deploy/private-dev/bin/configure-keycloak-identity")
mail = load("private_dev_keycloak_mail", "deploy/private-dev/bin/configure-keycloak-mail")

# The desired policy is the production import's, never a second copy of its values.
production = json.loads(identity.production_import.read_text(encoding="utf-8"))
desired = identity.desired_policy()
assert desired["settings"] == {name: production[name] for name in identity.MANAGED_REALM_SETTINGS}
assert desired["settings"]["verifyEmail"] is True and desired["settings"]["registrationEmailAsUsername"] is True
assert list(identity.MANAGED_REALM_SETTINGS)[-2:] == ["registrationEmailAsUsername", "verifyEmail"]
for excluded in ("realm", "sslRequired", "smtpServer", "clients", "registrationFlow"):
    assert excluded not in identity.MANAGED_REALM_SETTINGS, excluded
assert [attribute["name"] for attribute in desired["profileAttributes"]] == ["username", "email"]
assert desired["registrationFlow"]["form"]["steps"] == [
    ("registration-user-creation", "REQUIRED"),
    ("registration-password-action", "REQUIRED"),
    ("registration-terms-and-conditions", "REQUIRED"),
]
assert "18 años" in desired["localization"]["acceptTerms"]

for address in (
    "player@example.test",
    "smoke@example.invalid",
    "local@localhost",
    "someone@mail.example.com",
    "x@EXAMPLE.ORG.",
    "a@b.example",
):
    assert identity.reserved_address(address), address
for address in ("hola@gameometro.com", "player@example.co", "player@test.com", "someone@notexample.com"):
    assert not identity.reserved_address(address), address

with tempfile.TemporaryDirectory() as directory:
    root = pathlib.Path(directory)
    secrets = root / "secrets"
    secrets.mkdir()
    (secrets / mail.RELAY_USERNAME_SECRET).write_text("relay-user\n", encoding="utf-8")
    (secrets / mail.RELAY_PASSWORD_SECRET).write_text("relay-password\n", encoding="utf-8")
    valid = {
        "PRIVATE_DEV_SECRETS_DIR": str(secrets),
        "PRIVATE_DEV_SMTP_RELAY_HOST": "in-v3.mailjet.com",
        "PRIVATE_DEV_SMTP_RELAY_PORT": "587",
        "PRIVATE_DEV_SMTP_RELAY_FROM": "no-reply@mail.gameometro.com",
        "PRIVATE_DEV_SMTP_RELAY_REPLY_TO": "hola@gameometro.com",
    }

    def relay(**overrides: str) -> tuple[dict[str, str], str]:
        values = {**valid, **overrides}
        env_file = root / "runtime.env"
        env_file.write_text("".join(f"{key}={value}\n" for key, value in values.items() if value), encoding="utf-8")
        return mail.relay_inputs(env_file)

    smtp, password = relay()
    assert password == "relay-password" and "password" not in smtp
    assert smtp == {
        "host": "in-v3.mailjet.com",
        "port": "587",
        "from": "no-reply@mail.gameometro.com",
        "fromDisplayName": "Gameómetro",
        "replyTo": "hola@gameometro.com",
        "auth": "true",
        "user": "relay-user",
        "ssl": "false",
        "starttls": "true",
    }
    assert relay(PRIVATE_DEV_SMTP_RELAY_PORT="2525")[0]["port"] == "2525"
    for overrides in (
        {"PRIVATE_DEV_SMTP_RELAY_PORT": "465"},
        {"PRIVATE_DEV_SMTP_RELAY_PORT": "25"},
        {"PRIVATE_DEV_SMTP_RELAY_HOST": "in-v3.mailjet.com:587"},
        {"PRIVATE_DEV_SMTP_RELAY_HOST": "127.0.0.1"},
        {"PRIVATE_DEV_SMTP_RELAY_FROM": "Gameómetro <no-reply@mail.gameometro.com>"},
        {"PRIVATE_DEV_SMTP_RELAY_REPLY_TO": "hola@gameometro.com,otro@gameometro.com"},
        {"PRIVATE_DEV_SMTP_RELAY_REPLY_TO": ""},
    ):
        try:
            relay(**overrides)
        except mail.ProvisioningError:
            pass
        else:
            raise AssertionError(f"invalid relay input accepted: {overrides}")
    # The optional secret files exist empty until the owner fills them; relay then refuses.
    (secrets / mail.RELAY_PASSWORD_SECRET).write_text("", encoding="utf-8")
    try:
        relay()
    except mail.ProvisioningError as error:
        assert "relay-password" not in str(error)
    else:
        raise AssertionError("an empty relay password was accepted")

    # A relay map is aligned only with its password present; Mailpit only without any credential.
    relay_desired = mail.desired_settings(smtp)
    aligned = {**relay_desired, "smtpServer": {**smtp, "password": mail.MASKED_PASSWORD}}
    assert mail.differing_settings(aligned, relay_desired) == []
    assert mail.differing_settings({**relay_desired, "smtpServer": smtp}, relay_desired) == ["smtpServer"]
    mailpit_desired = mail.desired_settings()
    assert mail.differing_settings(aligned, mailpit_desired) == ["smtpServer"]
    leftover = {**mailpit_desired, "smtpServer": {**mailpit_desired["smtpServer"], "user": "relay-user"}}
    assert mail.differing_settings(leftover, mailpit_desired) == ["smtpServer"]

print("Private-dev identity policy derivation and relay input validation passed.")
