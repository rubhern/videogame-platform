#!/usr/bin/env python3

"""Static contract for the public-production realm import and its isolation from local/private-dev."""

from __future__ import annotations

import json
import pathlib
import re
import sys

sys.dont_write_bytecode = True

repository_root = pathlib.Path(__file__).resolve().parents[1]
import_directory = repository_root / "deploy/public-production/keycloak/import"
realm_file = import_directory / "gameometro-realm.json"
shared_realm_file = repository_root / "docker/keycloak/import/videogame-platform-realm.json"

text = realm_file.read_text(encoding="utf-8")
realm = json.loads(text)
shared_realm = json.loads(shared_realm_file.read_text(encoding="utf-8"))
application = "https://${APPLICATION_PUBLIC_HOST}"

# --- Isolation from the shared local/private-dev configuration ---
assert [path.name for path in import_directory.iterdir()] == [realm_file.name], (
    "the production import directory must contain only the production realm"
)
assert realm["realm"] == "gameometro" and realm["realm"] != shared_realm["realm"]
assert "users" not in realm and "federatedUsers" not in realm, "production imports no accounts"
assert "identityProviders" not in realm and "userFederationProviders" not in realm
for forbidden in (
    "mailpit",
    ".invalid",
    "localhost",
    "127.0.0.1",
    "http://",
    "APPLICATION_PUBLIC_ORIGIN",
    "LOCAL_TEST_USER",
    "vgpDeploymentSmoke",
    "videogame-platform-users",
):
    assert forbidden not in text, f"production realm contains a local/private-dev value: {forbidden}"
# No local or private-dev executable mounts or reads the production configuration.
local_and_private_dev = [
    repository_root / name
    for name in (
        "compose.yaml",
        "compose.observability.yaml",
        "compose.localization.yaml",
        "scripts/local-dependencies.sh",
        "scripts/validate-identity.sh",
    )
]
local_and_private_dev += [
    path for path in (repository_root / "deploy/private-dev").rglob("*") if path.is_file() and path.suffix != ".md"
]
# The private-dev identity command reads the import as its single policy source and applies a
# subset through the Admin API; it never mounts or imports it.
policy_reader = repository_root / "deploy/private-dev/bin/configure-keycloak-identity"
for path in local_and_private_dev:
    if path == policy_reader:
        continue
    assert "public-production/" not in path.read_text(encoding="utf-8", errors="ignore"), path
assert "public-production/keycloak/import/gameometro-realm.json" in policy_reader.read_text(encoding="utf-8")

# Only reviewed runtime inputs are substituted at import; display-name keys are lower case.
placeholders = set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)\}", text))
assert placeholders == {
    "APPLICATION_PUBLIC_HOST",
    "KEYCLOAK_BFF_CLIENT_SECRET",
    "KEYCLOAK_SMTP_HOST",
    "KEYCLOAK_SMTP_PORT",
    "KEYCLOAK_SMTP_FROM",
    "KEYCLOAK_SMTP_USER",
    "KEYCLOAK_SMTP_PASSWORD",
}, placeholders
assert not re.search(r"\$\{(?!username\}|email\})[a-z]", text), "unexpected lower-case placeholder"

# --- Account and registration policy ---
expected_policy = {
    "enabled": True,
    "sslRequired": "all",
    "loginTheme": "gameometro",
    "emailTheme": "gameometro",
    "internationalizationEnabled": True,
    "supportedLocales": ["es"],
    "defaultLocale": "es",
    "registrationAllowed": True,
    "registrationEmailAsUsername": True,
    "loginWithEmailAllowed": True,
    "duplicateEmailsAllowed": False,
    "editUsernameAllowed": False,
    "verifyEmail": True,
    "resetPasswordAllowed": True,
    "rememberMe": True,
    "registrationFlow": "gameometro registration",
}
for name, value in expected_policy.items():
    assert realm.get(name) == value, f"{name}={realm.get(name)!r}"

policies = dict(re.findall(r"([A-Za-z]+)(?:\(([^)]*)\))?", realm["passwordPolicy"]))
policies.pop("and", None)
assert policies == {"length": "12", "maxLength": "128", "notEmail": "", "notContainsUsername": ""}, policies

profile = json.loads(
    realm["components"]["org.keycloak.userprofile.UserProfileProvider"][0]["config"]["kc.user.profile.config"][0]
)
assert "unmanagedAttributePolicy" not in profile, "unmanaged attributes stay disabled"
attributes = {attribute["name"]: attribute for attribute in profile["attributes"]}
assert set(attributes) == {"username", "email"}, sorted(attributes)
assert attributes["email"]["required"] == {"roles": ["user"]}

flows = {flow["alias"]: flow for flow in realm["authenticationFlows"]}
form = flows["gameometro registration form"]["authenticationExecutions"]
assert {execution["authenticator"]: execution["requirement"] for execution in form} == {
    "registration-user-creation": "REQUIRED",
    "registration-password-action": "REQUIRED",
    "registration-terms-and-conditions": "REQUIRED",
}, "registration needs the declaration and no third-party captcha"
top = flows["gameometro registration"]["authenticationExecutions"]
assert [(step["authenticator"], step["flowAlias"], step["requirement"]) for step in top] == [
    ("registration-page-form", "gameometro registration form", "REQUIRED")
]

declaration = realm["localizationTexts"]["es"]
assert "18 años" in declaration["acceptTerms"] and "condiciones" in declaration["acceptTerms"]
assert "18 años" in declaration["termsAcceptanceRequired"]
assert "email" in declaration["emailInstruction"] and "usuario" not in declaration["emailInstruction"]
assert "nacimiento" not in text and "DNI" not in text
# A temporary lockout must read exactly like a wrong password, so it reveals nothing.
assert declaration["accountTemporarilyDisabledMessage"] == declaration["invalidUserMessage"]

# --- Brute force, sessions and tokens ---
assert realm["bruteForceProtected"] is True
assert realm["permanentLockout"] is False and realm["maxTemporaryLockouts"] == 0, "lockout stays temporary"
assert 3 <= realm["failureFactor"] <= 10
assert 0 < realm["waitIncrementSeconds"] <= realm["maxFailureWaitSeconds"] <= 900
assert realm["maxDeltaTimeSeconds"] >= 3600
assert realm["ssoSessionIdleTimeout"] == 1800 and realm["ssoSessionMaxLifespan"] == 28800
assert realm["ssoSessionIdleTimeoutRememberMe"] == 604800 and realm["ssoSessionMaxLifespanRememberMe"] == 2592000
assert realm["clientSessionIdleTimeout"] == 0 and realm["clientSessionMaxLifespan"] == 0, "client sessions inherit SSO"
assert realm["offlineSessionMaxLifespanEnabled"] is True
assert realm["offlineSessionMaxLifespan"] <= realm["ssoSessionMaxLifespan"]
assert realm["accessTokenLifespan"] == 300 and realm["accessCodeLifespan"] == 60
assert realm["actionTokenGeneratedByUserLifespan"] == 300, "#273 reset links keep their five-minute lifetime"
assert realm["revokeRefreshToken"] is True and realm["refreshTokenMaxReuse"] == 0

# --- Events: nothing stored, nothing logged by a listener ---
assert realm["eventsEnabled"] is False and realm["eventsListeners"] == [] and realm["enabledEventTypes"] == []
assert realm["adminEventsEnabled"] is False and realm["adminEventsDetailsEnabled"] is False

# --- Self-service deletion stays unavailable ---
# Keycloak's defaults neither grant the delete-account role nor enable its required action.
# The only declared role is the BFF client's own catalogue operator role (#247); no realm role,
# default role or composite exists, and nobody holds it by default.
assert realm["roles"] == {
    "client": {
        "videogame-platform-bff": [
            {
                "name": "catalogue-operator",
                "description": "Reads catalogue synchronization status and coverage in the catalogue administration view.",
                "composite": False,
                "clientRole": True,
            }
        ]
    }
}
assert "defaultRole" not in realm and "requiredActions" not in realm
assert "delete-account" not in text and "delete_account" not in text, "no deletion capability is configured"

# --- Mail: authenticated, encrypted submission from runtime secrets ---
smtp = realm["smtpServer"]
assert smtp["auth"] == "true" and smtp["starttls"] == "true" and smtp["ssl"] == "false"
assert smtp["password"] == "${KEYCLOAK_SMTP_PASSWORD}" and smtp["user"] == "${KEYCLOAK_SMTP_USER}"
assert "debug" not in smtp

# --- The only client: confidential BFF, code flow with PKCE, exact HTTPS URIs ---
assert len(realm["clients"]) == 1
client = realm["clients"][0]
assert client["clientId"] == "videogame-platform-bff"
assert client["secret"] == "${KEYCLOAK_BFF_CLIENT_SECRET}"
for name, value in {
    "enabled": True,
    "publicClient": False,
    "bearerOnly": False,
    "clientAuthenticatorType": "client-secret",
    "consentRequired": False,
    "standardFlowEnabled": True,
    "implicitFlowEnabled": False,
    "directAccessGrantsEnabled": False,
    "serviceAccountsEnabled": False,
    "authorizationServicesEnabled": False,
    "frontchannelLogout": False,
    "fullScopeAllowed": False,
    "rootUrl": application,
    "redirectUris": [f"{application}/login/oauth2/code/keycloak"],
    "webOrigins": [],
    "defaultClientScopes": ["basic"],
    "optionalClientScopes": [],
}.items():
    assert client.get(name) == value, f"client {name}={client.get(name)!r}"
client_attributes = client["attributes"]
assert client_attributes["pkce.code.challenge.method"] == "S256"
assert client_attributes["post.logout.redirect.uris"] == f"{application}/"
for disabled_grant in (
    "oauth2.device.authorization.grant.enabled",
    "oidc.ciba.grant.enabled",
    "standard.token.exchange.enabled",
):
    assert client_attributes[disabled_grant] == "false", disabled_grant
assert "*" not in json.dumps(client), "no wildcard origin or redirect"
assert not any(name.startswith("backchannel.") for name in client_attributes), "ADR-0027: no back-channel logout"
# The BFF reads product roles only from its own ID token: no access token, user info or
# introspection carries them (#247).
assert client["protocolMappers"] == [
    {
        "name": "product roles",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-client-role-mapper",
        "consentRequired": False,
        "config": {
            "usermodel.clientRoleMapping.clientId": "videogame-platform-bff",
            "claim.name": "product_roles",
            "jsonType.label": "String",
            "multivalued": "true",
            "id.token.claim": "true",
            "access.token.claim": "false",
            "userinfo.token.claim": "false",
            "introspection.token.claim": "false",
            "lightweight.claim": "false",
        },
    },
    {"name": "native remember me", "protocol": "openid-connect", "protocolMapper": "gameometro-remember-me", "consentRequired": False, "config": {}},
]
# Private-dev and local realms are aligned from their own import, which must declare the same
# role and mapper as production (configure-keycloak-operator reads it, never this file).
local_realm = json.loads((repository_root / "docker/keycloak/import/videogame-platform-realm.json").read_text(encoding="utf-8"))
[local_client] = [entry for entry in local_realm["clients"] if entry["clientId"] == client["clientId"]]
assert local_realm["roles"] == realm["roles"], "local and production realms declare the same client roles"
assert local_client["protocolMappers"] == client["protocolMappers"], "local and production BFF mappers match"

print("Production realm import is isolated, minimal and matches the reviewed identity policy.")

# --- Abuse runtime policy is independent from the imported realm and shared by rehearsal ---
policy_path = repository_root / "deploy/public-production/keycloak/abuse.env"
runtime = dict(line.split("=", 1) for line in policy_path.read_text().splitlines()
               if line and not line.startswith("#"))
assert runtime["KC_HTTP_POOL_MAX_THREADS"] == "8"
assert runtime["KC_HTTP_MAX_QUEUED_REQUESTS"] == "16"
assert runtime["KC_DB_POOL_MAX_SIZE"] == "5"
assert runtime["KC_DB_POOL_INITIAL_SIZE"] == runtime["KC_DB_POOL_MIN_SIZE"] == "1"
assert runtime["KC_EVENT_METRICS_USER_TAGS"] == "realm"
assert set(runtime["KC_EVENT_METRICS_USER_EVENTS"].split(",")) == {
    "login", "register", "verify_email", "send_verify_email", "send_reset_password",
    "reset_password", "user_disabled_by_temporary_lockout",
}
private_compose = (repository_root / "deploy/private-dev/compose.yaml").read_text()
for name, value in runtime.items():
    assert re.search(r"^\s+" + re.escape(name) + r': "?' + re.escape(value) + r'"?$',
                     private_compose, re.MULTILINE), name
runner = (repository_root / "scripts/validate-production-identity.sh").read_text()
assert "--build-arg KEYCLOAK_EVENT_METRICS_USER_ENABLED=true" in runner
assert '--env-file "$repository_root/deploy/public-production/keycloak/abuse.env"' in runner
print("Bounded identity runtime and native security metrics are selected for disposable proof.")
