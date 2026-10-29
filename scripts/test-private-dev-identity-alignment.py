#!/usr/bin/env python3

"""Prove the private-dev identity alignment against a disposable copy of the shared realm.

Run by scripts/validate-private-dev-identity.sh. Accounts are seeded the way vgpdev holds
them before the change, the reviewed commands are run as the operator runs them, and the
journey proves that accounts keep their identity while the production policy applies.
Each Browser is one device; redirects stop at the BFF callback, so a returned
authorization code is the only evidence that Keycloak let an account in.
"""

from __future__ import annotations

import base64
import hashlib
import html
import http.cookiejar
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import NamedTuple

sys.dont_write_bytecode = True

repository_root = pathlib.Path(__file__).resolve().parents[1]
identity_command = repository_root / "deploy/private-dev/bin/configure-keycloak-identity"
mail_command = repository_root / "deploy/private-dev/bin/configure-keycloak-mail"


def load(name: str, path: pathlib.Path) -> object:
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


provisioning = load("private_dev_oidc_provisioning", repository_root / "deploy/private-dev/bin/provision-oidc-smoke-user")
identity = load("private_dev_keycloak_identity", identity_command)


def required_environment(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise AssertionError(f"Missing required integration-test environment variable: {name}")
    return value


ORIGIN = required_environment("KEYCLOAK_TEST_ORIGIN")
ADMIN_USERNAME = required_environment("KEYCLOAK_TEST_ADMIN_USERNAME")
ADMIN_PASSWORD = required_environment("KEYCLOAK_TEST_ADMIN_PASSWORD")
CLIENT_SECRET = required_environment("KEYCLOAK_TEST_CLIENT_SECRET")
APPLICATION = required_environment("APPLICATION_TEST_ORIGIN")
MAILPIT = required_environment("MAILPIT_API_ORIGIN")
RELAY = required_environment("RELAY_API_ORIGIN")
RELAY_ENV_FILE = required_environment("RELAY_ENV_FILE")
EVIDENCE = pathlib.Path(required_environment("TEST_EVIDENCE_DIR"))

REALM = "videogame-platform"
CLIENT_ID = "videogame-platform-bff"
REDIRECT_URI = f"{APPLICATION}/login/oauth2/code/keycloak"
ISSUER = f"{ORIGIN}/realms/{REALM}"
ADMIN_REALM = f"/admin/realms/{REALM}"
INVALID_CREDENTIALS = "Email o contraseña incorrectos."
DECLARATION_REQUIRED = "Para crear tu cuenta, confirma que tienes 18 años o más y aceptas las condiciones de uso."
VERIFY_TITLE = "Verifica tu email"
RECOVERY_SENT = "Si el email corresponde a una cuenta, recibirás en breve un enlace para crear una nueva contraseña."

sensitive: list[str] = [ADMIN_PASSWORD]


def remember(*values: str) -> None:
    sensitive.extend(value for value in values if value)


class Response(NamedTuple):
    status: int
    location: str
    body: str


def page_text(body: str) -> str:
    without_code = re.sub(r"(?is)<(script|style)\b.*?</\1>", " ", body)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", without_code))).strip()


def form_action(body: str, form_id: str) -> str:
    match = re.search(rf'<form id="{form_id}"[^>]*action="([^"]+)"', body)
    assert match, f"page has no {form_id} form: {page_text(body)[:300]}"
    return html.unescape(match.group(1))


def form_fields(body: str, form_id: str) -> set[str]:
    start = body.index(f'<form id="{form_id}"')
    form = body[start : body.index("</form>", start)]
    fields = set()
    for tag in re.findall(r"<input\b[^>]*>", form):
        name = re.search(r'\bname="([^"]+)"', tag)
        kind = re.search(r'\btype="([^"]+)"', tag)
        if name and (kind is None or kind.group(1) not in ("hidden", "submit")):
            fields.add(name.group(1))
    return fields


def page_link(body: str, path_fragment: str) -> str:
    match = re.search(rf'href="([^"]*{re.escape(path_fragment)}[^"]*)"', body)
    assert match, f"page has no link to {path_fragment}"
    target = html.unescape(match.group(1))
    return target if target.startswith("https://") else ORIGIN + target


class Browser:
    def __init__(self) -> None:
        class StopAtApplication(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, msg, headers, new_url):  # noqa: N802
                if new_url.startswith(REDIRECT_URI) or not new_url.startswith(ORIGIN + "/"):
                    return None
                return super().redirect_request(request, fp, code, msg, headers, new_url)

        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), StopAtApplication()
        )

    def open(self, url: str, fields: dict[str, str] | None = None) -> Response:
        data = urllib.parse.urlencode(fields).encode() if fields is not None else None
        try:
            with self.opener.open(url, data, timeout=20) as response:
                return Response(response.status, "", response.read().decode())
        except urllib.error.HTTPError as error:
            return Response(error.code, error.headers.get("Location") or "", error.read().decode(errors="replace"))

    def authorize(self) -> tuple[Response, str]:
        verifier = secrets.token_urlsafe(48)
        query = urllib.parse.urlencode(
            {
                "client_id": CLIENT_ID,
                "response_type": "code",
                "scope": "openid",
                "redirect_uri": REDIRECT_URI,
                "state": secrets.token_urlsafe(16),
                "nonce": secrets.token_urlsafe(16),
                "code_challenge": base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode(),
                "code_challenge_method": "S256",
            }
        )
        return self.open(f"{ISSUER}/protocol/openid-connect/auth?{query}"), verifier


def code(response: Response) -> str:
    """The code Keycloak returned to the BFF callback, or '' if the browser stayed in Keycloak."""
    if response.status not in (302, 303) or not response.location.startswith(REDIRECT_URI):
        return ""
    returned = urllib.parse.parse_qs(urllib.parse.urlparse(response.location).query).get("code", [""])[0]
    remember(returned, returned.split(".", 1)[0])
    return returned


def sign_in(identifier: str, password: str) -> tuple[Response, str]:
    browser = Browser()
    login, verifier = browser.authorize()
    answer = browser.open(form_action(login.body, "kc-form-login"), {"username": identifier, "password": password})
    return answer, verifier


def signs_in(identifier: str, password: str) -> bool:
    return bool(code(sign_in(identifier, password)[0]))


def admin(method: str, path: str, body: object | None = None) -> tuple[int, object]:
    credentials = urllib.parse.urlencode(
        {"client_id": "admin-cli", "grant_type": "password", "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}
    ).encode()
    with urllib.request.urlopen(f"{ORIGIN}/realms/master/protocol/openid-connect/token", credentials) as response:
        token = json.load(response)["access_token"]
    request = urllib.request.Request(
        ORIGIN + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")


def get(path: str) -> object:
    status, body = admin("GET", path)
    assert status == 200, (path, status, body)
    return body


def account_by_email(email: str) -> dict | None:
    users = get(f"{ADMIN_REALM}/users?" + urllib.parse.urlencode({"email": email, "exact": "true", "briefRepresentation": "false"}))
    return users[0] if users else None


def accounts() -> dict[str, dict]:
    return {user["id"]: user for user in get(f"{ADMIN_REALM}/users?briefRepresentation=false&max=100")}


def stored_credentials(user_id: str) -> list[tuple[str, str, int]]:
    return [(item["id"], item["type"], item["createdDate"]) for item in get(f"{ADMIN_REALM}/users/{user_id}/credentials")]


def seed(username: str, email: str, verified: bool, password: str) -> str:
    """An account as private-dev holds it today: username sign-in, names, any password length."""
    remember(email, password)
    status, _ = admin(
        "POST",
        f"{ADMIN_REALM}/users",
        {
            "username": username,
            "email": email,
            "emailVerified": verified,
            "enabled": True,
            "firstName": "Synthetic",
            "lastName": "Player",
            "credentials": [{"type": "password", "value": password, "temporary": False}],
        },
    )
    assert status == 201, status
    return account_by_email(email)["id"]


def sink_messages(origin: str, address: str, subject: str, count: int = 1) -> list[dict]:
    query = "/api/v1/search?" + urllib.parse.urlencode({"query": f'to:"{address}" subject:"{subject}"'})
    for _ in range(40):
        with urllib.request.urlopen(origin + query, timeout=10) as response:
            found = json.load(response)["messages"]
        if len(found) >= count:
            details = []
            for summary in found:
                with urllib.request.urlopen(f"{origin}/api/v1/message/{summary['ID']}", timeout=10) as response:
                    details.append(json.load(response))
            return details
        time.sleep(0.5)
    raise AssertionError(f"expected {count} message(s) with subject '{subject}' for {address} in {origin}")


def sink_count(origin: str, address: str) -> int:
    query = "/api/v1/search?" + urllib.parse.urlencode({"query": f'to:"{address}"'})
    with urllib.request.urlopen(origin + query, timeout=10) as response:
        return json.load(response)["messages_count"]


def action_link(message: dict) -> str:
    match = re.search(r"https://\S+/login-actions/action-token\?\S+", message["Text"])
    assert match, "the email carries no Keycloak action-token link"
    link = match.group(0)
    assert link.startswith(f"{ISSUER}/login-actions/action-token?"), link
    remember(urllib.parse.parse_qs(urllib.parse.urlparse(link).query)["key"][0])
    return link


def choose_password(browser: Browser, page: Response, password: str) -> Response:
    assert "kc-passwd-update-form" in page.body, page_text(page.body)[:300]
    rejected = "Corta-2026!"
    refused = browser.open(form_action(page.body, "kc-passwd-update-form"), {"password-new": rejected, "password-confirm": rejected})
    assert "La contraseña debe tener al menos 12 caracteres." in page_text(refused.body), page_text(refused.body)[:300]
    remember(password)
    return browser.open(form_action(refused.body, "kc-passwd-update-form"), {"password-new": password, "password-confirm": password})


def command(path: pathlib.Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [sys.executable, str(path), "--origin", ORIGIN, "--admin-username", ADMIN_USERNAME, "--admin-password-file", "/dev/stdin", *arguments],
        input=ADMIN_PASSWORD,
        capture_output=True,
        check=False,
        text=True,
    )
    output = result.stdout + result.stderr
    assert ADMIN_PASSWORD not in output and CLIENT_SECRET not in output, "a command printed a credential"
    return result


def synthetic_password() -> str:
    password = "Gm-" + secrets.token_urlsafe(18)
    remember(password)
    return password


def unique(purpose: str, domain: str = "example.test") -> str:
    email = f"{purpose}-{secrets.token_hex(4)}@{domain}"
    remember(email)
    return email


# 1. Private-dev before the change: username sign-in, optional verification, names, short passwords.
[legacy_client] = get(f"{ADMIN_REALM}/clients?clientId={CLIENT_ID}")
for mapper in legacy_client["protocolMappers"]:
    if mapper["protocolMapper"] == "gameometro-remember-me":
        assert admin("DELETE", f"{ADMIN_REALM}/clients/{legacy_client['id']}/protocol-mappers/models/{mapper['id']}")[0] == 204
# New local imports include the adult/terms flow and legal localization. Recreate
# the historical registration baseline so apply/restore still prove its migration.
assert admin("PUT", ADMIN_REALM, {"registrationFlow": identity.BUILT_IN_REGISTRATION_FLOW, "rememberMe": False, "ssoSessionMaxLifespan": 36000, "ssoSessionIdleTimeoutRememberMe": 0, "ssoSessionMaxLifespanRememberMe": 0})[0] == 204
for flow in get(f"{ADMIN_REALM}/authentication/flows"):
    if flow["alias"] == identity.desired_policy()["registrationFlow"]["alias"]:
        assert admin("DELETE", f"{ADMIN_REALM}/authentication/flows/{flow['id']}")[0] == 204
for key in get(f"{ADMIN_REALM}/localization/es"):
    assert admin("DELETE", f"{ADMIN_REALM}/localization/es/{urllib.parse.quote(key, safe='')}")[0] == 204
legacy_password = "legacy-1"
verified_name, verified_email = "legacy-verified", unique("legacy-verified", "gameometro.com")
reserved_name, reserved_email = "legacy-reserved", unique("legacy-reserved")
pending_name, pending_email = "legacy-pending", unique("legacy-pending", "gameometro.com")
verified_id = seed(verified_name, verified_email, True, legacy_password)
reserved_id = seed(reserved_name, reserved_email, False, legacy_password)
pending_id = seed(pending_name, pending_email, False, legacy_password)
smoke_username = "integration-deployment-smoke"
smoke_password = synthetic_password()
smoke_admin = provisioning.KeycloakAdmin(ORIGIN)
smoke_admin.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
smoke_admin.ensure_smoke_marker_profile()
provisioning.provision_smoke_user(smoke_admin, smoke_username, smoke_password)
smoke_id = smoke_admin.find_users(smoke_username)[0]["id"]
# The vgpdev smoke account predates its verified flag.
assert admin("PUT", f"{ADMIN_REALM}/users/{smoke_id}", {"emailVerified": False})[0] == 204
assert signs_in(verified_name, legacy_password) and signs_in(reserved_name, legacy_password)

before_accounts = accounts()
before_credentials = {user_id: stored_credentials(user_id) for user_id in before_accounts}
[bff_client] = get(f"{ADMIN_REALM}/clients?clientId={CLIENT_ID}")
before_realm = get(ADMIN_REALM)

# Local startup uses the same command in session-only mode. Preserve every other realm
# field, client setting, credential and account; prove apply/check/no-op/restore on real Keycloak.
checked = command(identity_command, "--sessions-only", "--check")
assert checked.returncode == 1 and "rememberMeMapper" in checked.stderr, checked.stderr
assert command(identity_command, "--sessions-only").returncode == 2
session_directory = EVIDENCE / "sessions"
session_directory.mkdir(mode=0o700)
aligned = command(identity_command, "--sessions-only", "--snapshot-dir", str(session_directory))
assert aligned.returncode == 0, aligned.stdout + aligned.stderr
[session_snapshot] = session_directory.glob("keycloak-identity-before-*.json")
assert session_snapshot.stat().st_mode & 0o777 == 0o600
assert command(identity_command, "--sessions-only", "--check").returncode == 0
repeated = command(identity_command, "--sessions-only", "--snapshot-dir", str(session_directory))
assert repeated.returncode == 0 and "nothing changed" in repeated.stdout
assert len(list(session_directory.glob("*.json"))) == 1
session_realm = get(ADMIN_REALM)
for name in identity.SESSION_SETTINGS:
    assert session_realm[name] == identity.desired_policy()["settings"][name]
for name, value in before_realm.items():
    if name not in identity.SESSION_SETTINGS:
        assert session_realm.get(name) == value, f"session alignment changed unrelated setting: {name}"
[session_client] = get(f"{ADMIN_REALM}/clients?clientId={CLIENT_ID}")
assert {key: value for key, value in session_client.items() if key != "protocolMappers"} == {
    key: value for key, value in bff_client.items() if key != "protocolMappers"}
assert [mapper for mapper in session_client["protocolMappers"] if mapper["name"] != "native remember me"] == bff_client["protocolMappers"]
assert accounts() == before_accounts
assert {user_id: stored_credentials(user_id) for user_id in before_accounts} == before_credentials
assert command(identity_command, "--restore", str(session_snapshot)).returncode == 1
assert command(identity_command, "--sessions-only", "--restore", str(session_snapshot)).returncode == 0
assert command(identity_command, "--sessions-only", "--check").returncode == 1
assert get(ADMIN_REALM) == before_realm
[restored_session_client] = get(f"{ADMIN_REALM}/clients?clientId={CLIENT_ID}")
assert restored_session_client == bff_client

# The prepared production realm has a different name. Prove explicit targeting on a
# second disposable realm without touching private-dev's full identity settings.
assert admin("POST", "/admin/realms", {"realm": "gameometro", "enabled": True,
    "clients": [{"clientId": CLIENT_ID, "enabled": True, "protocol": "openid-connect"}]})[0] == 201
production_directory = EVIDENCE / "production-sessions"
production_directory.mkdir(mode=0o700)
configured = command(identity_command, "--realm", "gameometro", "--sessions-only", "--snapshot-dir", str(production_directory))
assert configured.returncode == 0, configured.stdout + configured.stderr
assert command(identity_command, "--realm", "gameometro", "--sessions-only", "--check").returncode == 0
for name in identity.SESSION_SETTINGS:
    assert get("/admin/realms/gameometro")[name] == identity.desired_policy()["settings"][name]
assert get(ADMIN_REALM) == before_realm
[production_snapshot] = production_directory.glob("*.json")
assert command(identity_command, "--realm", "gameometro", "--sessions-only", "--restore", str(production_snapshot)).returncode == 0
assert command(identity_command, "--realm", "gameometro", "--sessions-only", "--check").returncode == 1
assert admin("DELETE", "/admin/realms/gameometro")[0] == 204

# 2. The check reports drift and the account inventory; apply snapshots, then aligns; repeats are no-ops.
checked = command(identity_command, "--check")
assert checked.returncode == 1 and "verifyEmail" in checked.stderr and "registrationFlow" in checked.stderr, checked.stderr
assert "Accounts: 4; without email: 0; username differs from email: 4; unverified: 3 (2 with a reserved" in checked.stdout, checked.stdout
listed = command(identity_command, "--list-unverified")
assert listed.returncode == 0 and listed.stdout.split() == [pending_name, pending_email], listed.stdout
refused = command(identity_command)
assert refused.returncode == 2 and "--snapshot-dir" in refused.stderr
applied = command(identity_command, "--snapshot-dir", str(EVIDENCE))
assert applied.returncode == 0, applied.stdout + applied.stderr
assert "2 reserved-address account(s) marked verified" in applied.stdout, applied.stdout
[snapshot] = sorted(EVIDENCE.glob("keycloak-identity-before-*.json"))
assert oct(snapshot.stat().st_mode & 0o777) == "0o600"
assert reserved_email not in snapshot.read_text(encoding="utf-8") and legacy_password not in snapshot.read_text(encoding="utf-8")
assert command(identity_command, "--check").returncode == 0
repeated = command(identity_command, "--snapshot-dir", str(EVIDENCE))
assert repeated.returncode == 0 and "nothing changed" in repeated.stdout and len(list(EVIDENCE.glob("keycloak-*.json"))) == 1

# 3. Accounts keep their identity: the same IDs (so ratings stay attached), credentials and
#    addresses; only reserved addresses were marked verified. Only the native mode mapper is
#    added to the BFF; its identity, secret, redirects and other mappers are untouched.
after_accounts = accounts()
assert set(after_accounts) == set(before_accounts)
for user_id, before in before_accounts.items():
    after = after_accounts[user_id]
    assert after["email"] == before["email"] and after["enabled"] is True
    assert stored_credentials(user_id) == before_credentials[user_id], "credentials changed"
    assert after["emailVerified"] is (user_id != pending_id), (before["email"], after["emailVerified"])
    assert "firstName" not in after and "lastName" not in after, "names are no longer part of the profile"
[bff_client_after] = get(f"{ADMIN_REALM}/clients?clientId={CLIENT_ID}")
assert {k:v for k,v in bff_client_after.items() if k != "protocolMappers"} == {k:v for k,v in bff_client.items() if k != "protocolMappers"}
assert any(m["protocolMapper"] == "gameometro-remember-me" for m in bff_client_after["protocolMappers"])
after_realm = get(ADMIN_REALM)
managed = {"registrationFlow", *identity.MANAGED_REALM_SETTINGS}
for name in set(before_realm) | set(after_realm):
    if name not in managed:
        before_value, after_value = before_realm.get(name), after_realm.get(name)
        if isinstance(before_value, dict) and isinstance(after_value, dict):
            # Keycloak mirrors some managed settings into the realm attribute map.
            changed = {
                key
                for key in before_value.keys() | after_value.keys()
                if key not in managed and before_value.get(key) != after_value.get(key)
            }
            assert not changed, f"unmanaged realm setting changed: {name} {sorted(changed)}"
        else:
            assert before_value == after_value, f"unmanaged realm setting changed: {name}"
assert after_realm["sslRequired"] == "external" and after_realm["smtpServer"]["host"] == "mailpit"

# 4. Existing accounts sign in by email; username sign-in keeps working until Keycloak rewrites
#    the stored username; the old password stays valid because policies apply when one is set.
assert signs_in(verified_email, legacy_password) and signs_in(verified_name, legacy_password)
assert signs_in(reserved_email, legacy_password) and sink_count(MAILPIT, reserved_email) == 0
answer, _ = sign_in(pending_email, legacy_password)
assert not code(answer) and VERIFY_TITLE in page_text(answer.body), page_text(answer.body)[:300]
verifying = Browser()
confirmation = verifying.open(action_link(sink_messages(MAILPIT, pending_email, "Verifica tu email")[0]))
if "/login-actions/action-token" in confirmation.body:
    confirmation = verifying.open(page_link(confirmation.body, "/login-actions/action-token"))
assert accounts()[pending_id]["emailVerified"] is True, page_text(confirmation.body)[:300]
assert signs_in(pending_email, legacy_password), "a verified legacy account keeps its password"
answer, _ = sign_in(unique("nobody"), legacy_password)
assert not code(answer) and INVALID_CREDENTIALS in page_text(answer.body)

# 5. Registration asks only for email and the mandatory 18+ declaration; mail stays in Mailpit.
device = Browser()
login, _ = device.authorize()
assert form_fields(login.body, "kc-form-login") == {"username", "password", "rememberMe"}
registration = device.open(page_link(login.body, "/login-actions/registration"))
assert "Responsable: Rubén Hernández López" in page_text(registration.body)
assert "Para prestar el servicio utilizamos proveedores externos" in page_text(registration.body)
assert "no te pedimos que consientas" in page_text(registration.body)
assert "/privacidad" in registration.body and "/condiciones" in registration.body
assert "Texto aprobado por el titular" not in registration.body and "Internal editorial" not in registration.body
assert form_fields(registration.body, "kc-register-form") == {"email", "termsAccepted"}, form_fields(registration.body, "kc-register-form")
player = unique("player")
undeclared = device.open(form_action(registration.body, "kc-register-form"), {"email": player})
assert DECLARATION_REQUIRED in page_text(undeclared.body) and account_by_email(player) is None
created = device.open(form_action(undeclared.body, "kc-register-form"), {"email": player, "termsAccepted": "on", "firstName": "Synthetic"})
assert VERIFY_TITLE in page_text(created.body), page_text(created.body)[:300]
stored = account_by_email(player)
assert stored["username"] == player and stored["emailVerified"] is False and "firstName" not in stored
password_page = device.open(action_link(sink_messages(MAILPIT, player, "Verifica tu email")[0]))
player_password = synthetic_password()
finished = choose_password(device, password_page, player_password)
assert code(finished), finished.location or page_text(finished.body)[:300]
answer, verifier = sign_in(player, player_password)
player_code = code(answer)
basic = base64.b64encode(f"{CLIENT_ID}:{CLIENT_SECRET}".encode()).decode()
token_request = urllib.request.Request(
    f"{ISSUER}/protocol/openid-connect/token",
    urllib.parse.urlencode({"grant_type": "authorization_code", "code": player_code, "redirect_uri": REDIRECT_URI, "code_verifier": verifier}).encode(),
    {"Authorization": f"Basic {basic}", "Content-Type": "application/x-www-form-urlencoded"},
)
with urllib.request.urlopen(token_request) as response:
    tokens = json.load(response)
remember(tokens["access_token"], tokens["id_token"], tokens.get("refresh_token", ""))
id_token = tokens["id_token"].split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(id_token + "=" * (-len(id_token) % 4)))
assert claims["iss"] == ISSUER and claims["sub"] == stored["id"], "the private-dev issuer and subject are unchanged"

# 6. The smoke account signs in by its synthetic email; a username identifier is refused.
try:
    provisioning.provision_smoke_user(smoke_admin, smoke_username, smoke_password, email_as_username=smoke_admin.email_as_username())
    raise AssertionError("a username smoke identifier was accepted with email sign-in")
except provisioning.ProvisioningError:
    pass
provisioning.provision_smoke_user(smoke_admin, provisioning.SYNTHETIC_EMAIL, smoke_password, email_as_username=True)
assert signs_in(provisioning.SYNTHETIC_EMAIL, smoke_password) and sink_count(MAILPIT, provisioning.SYNTHETIC_EMAIL) == 0

# 7. Five spaced failures lock one account temporarily, indistinguishably from a wrong password.
target = unique("lockout")
target_password = synthetic_password()
target_id = seed(target, target, True, target_password)
for attempt in range(1, 6):
    if attempt > 1:
        time.sleep(2.5)
    answer, _ = sign_in(target, synthetic_password())
    assert not code(answer) and INVALID_CREDENTIALS in page_text(answer.body)
assert get(f"{ADMIN_REALM}/attack-detection/brute-force/users/{target_id}")["disabled"] is True
answer, _ = sign_in(target, target_password)
assert not code(answer) and INVALID_CREDENTIALS in page_text(answer.body), "the lockout is indistinguishable"
assert signs_in(verified_email, legacy_password), "other accounts keep signing in"
assert admin("DELETE", f"{ADMIN_REALM}/attack-detection/brute-force/users/{target_id}")[0] == 204

# 8. The explicit relay transport sends through authenticated STARTTLS with the reply address,
#    and the #273 recovery journey is unchanged apart from the email identifier.
relay = command(mail_command, "--transport", "relay", "--relay-env-file", RELAY_ENV_FILE)
assert relay.returncode == 0 and "credentials were not printed" in relay.stdout, relay.stdout + relay.stderr
assert command(mail_command, "--transport", "relay", "--relay-env-file", RELAY_ENV_FILE, "--check").returncode == 0
assert command(mail_command, "--check").returncode == 1, "the Mailpit check reports the relay"
smtp = get(ADMIN_REALM)["smtpServer"]
assert smtp["password"] == "**********" and smtp["auth"] == "true" and smtp["starttls"] == "true" and smtp["ssl"] == "false"
unknown = unique("unknown")
for identifier in (unknown, verified_email):
    recovering = Browser()
    login, _ = recovering.authorize()
    page = recovering.open(page_link(login.body, "/login-actions/reset-credentials"))
    assert "Indica tu email." in page_text(page.body)
    answer = recovering.open(form_action(page.body, "kc-reset-password-form"), {"username": identifier})
    assert RECOVERY_SENT in page_text(answer.body), "recovery responses disclose nothing"
[reset_mail] = sink_messages(RELAY, verified_email, "contraseña")
assert reset_mail["Subject"] == "Crea una nueva contraseña de Gameómetro"
assert reset_mail["From"] == {"Name": "Gameómetro", "Address": "no-reply@mail.gameometro.test"}
assert reset_mail["ReplyTo"] == [{"Name": "", "Address": "hola@gameometro.test"}], reset_mail["ReplyTo"]
assert sink_count(MAILPIT, verified_email) == 0 and sink_count(RELAY, unknown) == 0
recovered_password = synthetic_password()
finished = choose_password(recovering, recovering.open(action_link(reset_mail)), recovered_password)
assert code(finished), finished.location or page_text(finished.body)[:300]
assert signs_in(verified_email, recovered_password) and not signs_in(verified_email, legacy_password)
assert accounts()[verified_id]["email"] == verified_email

# 9. Selecting Mailpit again removes the relay host and credentials from the realm.
back = command(mail_command)
assert back.returncode == 0 and command(mail_command, "--check").returncode == 0, back.stdout + back.stderr
smtp = get(ADMIN_REALM)["smtpServer"]
assert smtp["host"] == "mailpit" and "user" not in smtp and "password" not in smtp and "replyTo" not in smtp

# 10. Restoring the snapshot returns the previous policy; stored usernames were never rewritten.
restored = command(identity_command, "--restore", str(snapshot))
assert restored.returncode == 0, restored.stdout + restored.stderr
assert command(identity_command, "--check").returncode == 1
realm = get(ADMIN_REALM)
assert realm["verifyEmail"] is False and realm["registrationEmailAsUsername"] is False and realm["registrationFlow"] == "registration"
assert "passwordPolicy" not in realm or not realm["passwordPolicy"]
assert "gameometro registration" not in {flow["alias"] for flow in get(f"{ADMIN_REALM}/authentication/flows")}
assert get(f"{ADMIN_REALM}/localization/es") == {}
[restored_client] = get(f"{ADMIN_REALM}/clients?clientId={CLIENT_ID}")
assert restored_client == bff_client, "rollback restores the absent mapper without touching the client"
assert realm["rememberMe"] is False and realm["ssoSessionMaxLifespanRememberMe"] == 0
restored_accounts = accounts()
for user_id, before in before_accounts.items():
    after = restored_accounts[user_id]
    assert (after["username"], after["email"], after.get("firstName")) == (before["username"], before["email"], before.get("firstName"))
    # The pending account verified its address by email during the journey; that stays.
    assert after["emailVerified"] is (before["emailVerified"] or user_id == pending_id)
assert signs_in(reserved_name, legacy_password)

# New snapshots include the mapper resource; old tooling must reject their new version.
# Current tooling also restores a genuine pre-mapper v1 shape without that field.
saved = json.loads(snapshot.read_text(encoding="utf-8"))
assert saved["version"] == 2 and saved["rememberMeMapper"] is None
legacy = {key: value for key, value in saved.items() if key != "rememberMeMapper"}
legacy["version"] = 1
legacy_snapshot = EVIDENCE / "legacy-identity-v1.json"
legacy_snapshot.write_text(json.dumps(legacy), encoding="utf-8")
legacy_snapshot.chmod(0o600)
assert command(identity_command, "--restore", str(legacy_snapshot)).returncode == 0
[legacy_restored_client] = get(f"{ADMIN_REALM}/clients?clientId={CLIENT_ID}")
assert legacy_restored_client == bff_client

EVIDENCE.joinpath("sensitive-values.txt").write_text("\n".join(sensitive) + "\n", encoding="utf-8")
print(
    "Private-dev identity alignment proven: identity-preserving apply and exact restore, email sign-in, "
    "18+ registration with verification, password policy, temporary lockout, smoke by email, #273 recovery "
    "through the authenticated relay transport and back to Mailpit."
)
