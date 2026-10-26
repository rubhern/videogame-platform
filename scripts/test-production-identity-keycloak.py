#!/usr/bin/env python3

"""Prove the public-production identity policy against a disposable Keycloak and mail sink.

Run by scripts/validate-production-identity.sh on an internal Docker network. Each Browser is
one device with its own cookies; redirects to the application stop at the BFF callback, so a
returned authorization code is the only evidence that Keycloak let an account in.
"""

from __future__ import annotations

import base64
import hashlib
import html
import http.cookiejar
import json
import os
import pathlib
import re
import secrets
import socket
import statistics
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import NamedTuple

sys.dont_write_bytecode = True


def required_environment(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise AssertionError(f"Missing required integration-test environment variable: {name}")
    return value


ORIGIN = required_environment("KEYCLOAK_TEST_ORIGIN")
TLS = ssl.create_default_context(cafile=required_environment("KEYCLOAK_TEST_CA_FILE"))
ADMIN_USERNAME = required_environment("KEYCLOAK_TEST_ADMIN_USERNAME")
ADMIN_PASSWORD = required_environment("KEYCLOAK_TEST_ADMIN_PASSWORD")
CLIENT_SECRET = required_environment("KEYCLOAK_TEST_CLIENT_SECRET")
APPLICATION = "https://" + required_environment("APPLICATION_TEST_HOST")
MAILPIT = required_environment("MAILPIT_API_ORIGIN")
SENDER = required_environment("MAIL_TEST_SENDER")
EVIDENCE = pathlib.Path(required_environment("TEST_EVIDENCE_DIR"))

REALM = "gameometro"
CLIENT_ID = "videogame-platform-bff"
REDIRECT_URI = f"{APPLICATION}/login/oauth2/code/keycloak"
ISSUER = f"{ORIGIN}/realms/{REALM}"
ADMIN_REALM = f"/admin/realms/{REALM}"

INVALID_CREDENTIALS = "Email o contraseña incorrectos."
DECLARATION_REQUIRED = "Para crear tu cuenta, confirma que tienes 18 años o más y aceptas las condiciones de uso."
DECLARATION_LABEL = "Declaro que tengo 18 años o más y acepto las condiciones de uso de Gameómetro."
VERIFY_TITLE = "Verifica tu email"
RECOVERY_SENT = "Si el email corresponde a una cuenta, recibirás en breve un enlace para crear una nueva contraseña."
# product_roles appears only for an account holding a BFF client role (#247).
ID_TOKEN_CLAIMS = {"exp", "iat", "auth_time", "jti", "iss", "aud", "sub", "typ", "azp", "nonce", "sid", "at_hash", "product_roles", "gameometro_remember_me"}

# Every credential, address, link key and token the journey handles must stay out of the log.
sensitive: list[str] = [socket.gethostbyname(socket.gethostname())]


# Keycloak logs the random first segment of a replayed code, after consuming it and revoking
# what it issued; only that deliberately replayed code may appear, and only in that warning.
consumed_codes: list[str] = []


def remember(*values: str) -> None:
    sensitive.extend(value for value in values if value)


def remember_code(code: str) -> None:
    remember(code, code.split(".", 1)[0])


class Response(NamedTuple):
    status: int
    url: str
    location: str
    body: str
    headers: dict[str, str]


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
    """One device: its own cookie jar, stopping at the client callback or any redirect leaving Keycloak."""

    def __init__(self, callback_uri: str = REDIRECT_URI) -> None:
        self.redirects: list[dict] = []

        class StopAtApplication(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, msg, headers, new_url):  # noqa: N802
                self_browser.redirects.append({
                    "status": code,
                    "from": urllib.parse.urlsplit(request.full_url).path,
                    "to": urllib.parse.urlsplit(new_url).path,
                })
                if new_url.startswith(callback_uri) or not new_url.startswith(ORIGIN + "/"):
                    return None
                return super().redirect_request(request, fp, code, msg, headers, new_url)

        self_browser = self
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=TLS),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            StopAtApplication(),
        )

    def open(self, url: str, fields: dict[str, str] | None = None) -> Response:
        data = urllib.parse.urlencode(fields).encode() if fields is not None else None
        try:
            with self.opener.open(url, data, timeout=20) as response:
                return Response(response.status, response.url, "", response.read().decode(), dict(response.headers))
        except urllib.error.HTTPError as error:
            return Response(error.code, url, error.headers.get("Location") or "", error.read().decode(errors="replace"), dict(error.headers))

    def authorize(self, **overrides: str | None) -> tuple[Response, str, str]:
        """Start the BFF's authorization request: code flow, S256 PKCE, state and nonce."""
        verifier = secrets.token_urlsafe(48)
        state = secrets.token_urlsafe(16)
        parameters: dict[str, str | None] = {
            "client_id": CLIENT_ID,
            "response_type": "code",
            "scope": "openid",
            "redirect_uri": REDIRECT_URI,
            "state": state,
            "nonce": secrets.token_urlsafe(16),
            "code_challenge": base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode(),
            "code_challenge_method": "S256",
        }
        parameters.update(overrides)
        query = urllib.parse.urlencode({name: value for name, value in parameters.items() if value is not None})
        return self.open(f"{ISSUER}/protocol/openid-connect/auth?{query}"), verifier, state


def callback(response: Response) -> dict[str, str]:
    """Parameters Keycloak returned to the BFF callback, or {} if the browser stayed in Keycloak."""
    if response.status not in (302, 303) or not response.location.startswith(REDIRECT_URI):
        return {}
    parsed = urllib.parse.urlparse(response.location)
    returned = urllib.parse.parse_qs(parsed.query or parsed.fragment)
    return {name: values[0] for name, values in returned.items()}


def admin(method: str, path: str, body: object | None = None) -> tuple[int, object]:
    # Master-realm admin tokens live 60 seconds, which the lockout wait outlasts.
    credentials = urllib.parse.urlencode(
        {"client_id": "admin-cli", "grant_type": "password", "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}
    ).encode()
    with urllib.request.urlopen(f"{ORIGIN}/realms/master/protocol/openid-connect/token", credentials, context=TLS) as response:
        token = json.load(response)["access_token"]
    request = urllib.request.Request(
        ORIGIN + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, context=TLS) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")


def account(email: str) -> dict | None:
    status, users = admin("GET", f"{ADMIN_REALM}/users?" + urllib.parse.urlencode({"email": email, "exact": "true"}))
    assert status == 200 and isinstance(users, list)
    return users[0] if users else None


def account_details(email: str) -> dict:
    user = account(email)
    assert user is not None, "account was not created"
    status, details = admin("GET", f"{ADMIN_REALM}/users/{user['id']}")
    assert status == 200 and isinstance(details, dict)
    return details


def credentials(user_id: str) -> list[str]:
    status, stored = admin("GET", f"{ADMIN_REALM}/users/{user_id}/credentials")
    assert status == 200 and isinstance(stored, list)
    return [credential["type"] for credential in stored]


def create_verified_account(email: str, password: str) -> str:
    """Seed a verified account through the Admin API; production never imports accounts."""
    status, _ = admin(
        "POST",
        f"{ADMIN_REALM}/users",
        {
            "username": email,
            "email": email,
            "emailVerified": True,
            "enabled": True,
            "credentials": [{"type": "password", "value": password, "temporary": False}],
        },
    )
    assert status == 201, status
    user = account(email)
    assert user is not None
    return user["id"]


def mailpit(path: str) -> dict:
    with urllib.request.urlopen(MAILPIT + path, timeout=10) as response:
        return json.load(response)


def messages(address: str, subject: str, count: int = 1) -> list[dict]:
    """Newest first, once at least `count` matching messages have been delivered."""
    query = "/api/v1/search?" + urllib.parse.urlencode({"query": f'to:"{address}" subject:"{subject}"'})
    for _ in range(40):
        found = mailpit(query)["messages"]
        if len(found) >= count:
            return [mailpit(f"/api/v1/message/{summary['ID']}") for summary in found]
        time.sleep(0.5)
    raise AssertionError(f"expected {count} message(s) with subject '{subject}' for {address}")


def action_link(message: dict) -> str:
    match = re.search(r"https://\S+/login-actions/action-token\?\S+", message["Text"])
    assert match, "the email carries no Keycloak action-token link"
    link = match.group(0)
    assert link.startswith(f"{ISSUER}/login-actions/action-token?"), link
    remember(urllib.parse.parse_qs(urllib.parse.urlparse(link).query)["key"][0])
    return link


def token_request(fields: dict[str, str], secret: str = CLIENT_SECRET) -> tuple[int, dict]:
    basic = base64.b64encode(f"{CLIENT_ID}:{secret}".encode()).decode()
    request = urllib.request.Request(
        f"{ISSUER}/protocol/openid-connect/token",
        urllib.parse.urlencode(fields).encode(),
        {"Authorization": f"Basic {basic}", "Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(request, context=TLS) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")
    remember(*(str(body.get(name, "")) for name in ("access_token", "refresh_token", "id_token")))
    return 200, body


def claims(jwt: str) -> dict:
    payload = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def sign_in(email: str, password: str) -> tuple[Response, str, str]:
    """A fresh device signs in; returns Keycloak's answer, the PKCE verifier and the state."""
    browser = Browser()
    login, verifier, state = browser.authorize()
    assert "kc-form-login" in login.body, page_text(login.body)[:300]
    return browser.open(form_action(login.body, "kc-form-login"), {"username": email, "password": password}), verifier, state


def register(browser: Browser, email: str, fields: dict[str, str]) -> Response:
    login, _, _ = browser.authorize()
    registration = browser.open(page_link(login.body, "/login-actions/registration"))
    return browser.open(form_action(registration.body, "kc-register-form"), {"email": email, **fields})


def choose_password(browser: Browser, page: Response, email: str, password: str) -> Response:
    """Prove the password policy on Keycloak's password step, then set an accepted password."""
    assert "kc-passwd-update-form" in page.body, page_text(page.body)[:300]
    user = account(email)
    assert user is not None
    stored_before = admin("GET", f"{ADMIN_REALM}/users/{user['id']}/credentials")
    for rejected, message in (
        ("Corta-2026!", "La contraseña debe tener al menos 12 caracteres."),
        ("x" * 129, "La contraseña puede tener como máximo 128 caracteres."),
        (email, "La contraseña no puede"),
        (f"Mi-{email}-2026", "La contraseña no puede contener tu email."),
    ):
        remember(rejected)
        page = browser.open(form_action(page.body, "kc-passwd-update-form"), {"password-new": rejected, "password-confirm": rejected})
        assert message in page_text(page.body), page_text(page.body)[:300]
        assert admin("GET", f"{ADMIN_REALM}/users/{user['id']}/credentials") == stored_before, "a rejected password was stored"
    remember(password)
    return browser.open(form_action(page.body, "kc-passwd-update-form"), {"password-new": password, "password-confirm": password})


def synthetic_password() -> str:
    """A fresh password that satisfies the realm policy; nothing reusable is committed."""
    password = "Gm-" + secrets.token_urlsafe(18)
    remember(password)
    return password


def unique_email(purpose: str) -> str:
    email = f"{purpose}-{secrets.token_hex(6)}@example.test"
    remember(email)
    return email


# 1. The instance serves only the production realm, imported without accounts or events.
with urllib.request.urlopen(f"{ISSUER}/.well-known/openid-configuration", context=TLS) as response:
    discovery = json.load(response)
assert discovery["issuer"] == ISSUER, discovery["issuer"]
status, realms = admin("GET", "/admin/realms")
assert status == 200 and {realm["realm"] for realm in realms} == {"master", REALM}, realms
try:
    urllib.request.urlopen(f"{ORIGIN}/realms/videogame-platform/.well-known/openid-configuration", context=TLS)
    raise AssertionError("the local/private-dev realm must not exist on the production instance")
except urllib.error.HTTPError as error:
    assert error.code == 404
status, count = admin("GET", f"{ADMIN_REALM}/users/count")
assert status == 200 and count == 0, "production imports no seed or synthetic accounts"
status, realm = admin("GET", ADMIN_REALM)
assert status == 200 and isinstance(realm, dict)
assert realm["smtpServer"]["password"] == "**********", "the SMTP secret is never returned"
assert realm["eventsEnabled"] is False and realm["eventsListeners"] == [] and realm["adminEventsEnabled"] is False
status, profile = admin("GET", f"{ADMIN_REALM}/users/profile")
assert status == 200 and {attribute["name"] for attribute in profile["attributes"]} == {"username", "email"}
status, delete_action = admin("GET", f"{ADMIN_REALM}/authentication/required-actions/delete_account")
assert status == 200 and delete_action["enabled"] is False
status, default_roles = admin("GET", f"{ADMIN_REALM}/roles/default-roles-{REALM}/composites")
assert status == 200 and "delete-account" not in {role["name"] for role in default_roles}
status, clients = admin("GET", f"{ADMIN_REALM}/clients?" + urllib.parse.urlencode({"clientId": CLIENT_ID}))
assert status == 200 and len(clients) == 1
status, secret = admin("GET", f"{ADMIN_REALM}/clients/{clients[0]['id']}/client-secret")
assert status == 200 and secret["value"] == CLIENT_SECRET, "the client secret comes from the runtime input"

# 2. Registration asks only for an email and the explicit adult and terms declaration.
device = Browser()
login, _, _ = device.authorize()
assert form_fields(login.body, "kc-form-login") == {"username", "password", "rememberMe"}
assert "Email" in page_text(login.body)
registration = device.open(page_link(login.body, "/login-actions/registration"))
assert form_fields(registration.body, "kc-register-form") == {"email", "termsAccepted"}, (
    "no name, birth date, identity document or pre-verification password is collected"
)
assert DECLARATION_LABEL in page_text(registration.body)

# 3. The declaration is mandatory: without it nothing is created.
undeclared = unique_email("undeclared")
refused = device.open(form_action(registration.body, "kc-register-form"), {"email": undeclared})
assert DECLARATION_REQUIRED in page_text(refused.body), page_text(refused.body)[:300]
assert account(undeclared) is None

# 4. With the declaration the account exists, extra submitted data is discarded and no password exists yet.
player = unique_email("player")
smuggled_password = synthetic_password()
created = device.open(
    form_action(refused.body, "kc-register-form"),
    {
        "email": player.upper(),
        "termsAccepted": "on",
        "firstName": "Synthetic",
        "lastName": "Person",
        "birthdate": "2000-01-01",
        "user.attributes.birthdate": "2000-01-01",
        "password": smuggled_password,
        "password-confirm": smuggled_password,
    },
)
assert not callback(created) and VERIFY_TITLE in page_text(created.body), page_text(created.body)[:300]
stored = account_details(player)
assert stored["username"] == player and stored["email"] == player and stored["emailVerified"] is False
assert set(stored.get("requiredActions", [])) >= {"VERIFY_EMAIL", "UPDATE_PASSWORD"}, stored.get("requiredActions")
# The declaration gates creation itself; Keycloak stores nothing beyond the email for it.
assert not stored.get("attributes") and "firstName" not in stored and "lastName" not in stored, stored
assert credentials(stored["id"]) == [], "no password exists before the email is verified"

# 5. Verification mail leaves through authenticated STARTTLS (the sink accepts nothing else).
[verification] = messages(player, "Verifica tu email")
assert verification["Subject"] == "Verifica tu email de Gameómetro"
assert verification["From"] == {"Name": "Gameómetro", "Address": SENDER}
assert "El enlace caduca en 5 minutos." in verification["Text"]
verification_link = action_link(verification)

# 6. Unverified accounts cannot sign in: the registration has no password, and a password
#    alone is never enough while the address is unverified.
answer, _, _ = sign_in(player, smuggled_password)
assert not callback(answer) and INVALID_CREDENTIALS in page_text(answer.body)
unverified = unique_email("unverified")
unverified_password = synthetic_password()
status, _ = admin(
    "POST",
    f"{ADMIN_REALM}/users",
    {
        "username": unverified,
        "email": unverified,
        "emailVerified": False,
        "enabled": True,
        "credentials": [{"type": "password", "value": unverified_password, "temporary": False}],
    },
)
assert status == 201
answer, _, _ = sign_in(unverified, unverified_password)
assert not callback(answer) and VERIFY_TITLE in page_text(answer.body), page_text(answer.body)[:300]
action_link(messages(unverified, "Verifica tu email")[0])

# 7. The link verifies the address from another device, then the policy-checked password is set.
other_device = Browser()
confirmation = other_device.open(verification_link)
assert f"Confirma que {player} es tu email." in page_text(confirmation.body), page_text(confirmation.body)[:300]
password_page = other_device.open(page_link(confirmation.body, "/login-actions/action-token"))
player_password = synthetic_password()
done = choose_password(other_device, password_page, player, player_password)
assert "Tu cuenta se ha actualizado." in page_text(done.body), page_text(done.body)[:300]
verified = account_details(player)
assert verified["emailVerified"] is True and verified.get("requiredActions", []) == []
assert credentials(verified["id"]) == ["password"]
reused = Browser().open(verification_link)
assert "Tu email ya estaba verificado." in page_text(reused.body) and "kc-passwd-update-form" not in reused.body

# 8. Sign-in returns a code only to the exact callback; the BFF's exchange gets minimal claims.
answer, verifier, state = sign_in(player, player_password)
returned = callback(answer)
assert returned.get("state") == state and returned.get("iss") == ISSUER and "code" in returned, answer.location
remember(returned["code"])
consumed_codes.append(returned["code"].split(".", 1)[0])
status, tokens = token_request(
    {"grant_type": "authorization_code", "code": returned["code"], "redirect_uri": REDIRECT_URI, "code_verifier": verifier}
)
assert status == 200 and tokens["scope"] == "openid", tokens
identity = claims(tokens["id_token"])
assert set(identity) <= ID_TOKEN_CLAIMS, f"unexpected ID-token claims: {set(identity) - ID_TOKEN_CLAIMS}"
assert identity["gameometro_remember_me"] is False, "registration without opt-in remains ordinary"
assert "gameometro_remember_me" not in claims(tokens["access_token"]), "the mode claim is ID-token-only"
assert not identity.get("product_roles"), "a newly registered account holds no product role"
assert identity["iss"] == ISSUER and identity["aud"] == CLIENT_ID and identity["sub"] == verified["id"]
assert claims(tokens["refresh_token"])["typ"] == "Refresh", "no offline token"
userinfo = urllib.request.Request(
    f"{ISSUER}/protocol/openid-connect/userinfo", headers={"Authorization": f"Bearer {tokens['access_token']}"}
)
with urllib.request.urlopen(userinfo, context=TLS) as response:
    assert json.load(response) == {"sub": verified["id"]}, "no profile data leaves Keycloak"
status, refreshed = token_request({"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"]})
assert status == 200 and refreshed["refresh_token"] != tokens["refresh_token"]
status, reuse = token_request({"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"]})
assert status == 400 and reuse["error"] == "invalid_grant", "a rotated refresh token is single-use"
status, replay = token_request(
    {"grant_type": "authorization_code", "code": returned["code"], "redirect_uri": REDIRECT_URI, "code_verifier": verifier}
)
assert status == 400 and replay["error"] == "invalid_grant"
status, revoked = token_request({"grant_type": "refresh_token", "refresh_token": refreshed["refresh_token"]})
assert status == 400 and revoked["error"] == "invalid_grant", "a replayed code revokes what it issued"

# 9. Verifying on the registering device resumes the original authorization to the BFF.
resuming = Browser()
same_device = unique_email("same-device")
pending = register(resuming, same_device, {"termsAccepted": "on"})
assert VERIFY_TITLE in page_text(pending.body)
password_page = resuming.open(action_link(messages(same_device, "Verifica tu email")[0]))
finished = choose_password(resuming, password_page, same_device, synthetic_password())
assert "code" in callback(finished), finished.location or page_text(finished.body)[:300]
remember_code(callback(finished)["code"])

# 10. The BFF client accepts only its exact HTTPS callback, S256 PKCE and the code flow.
for redirect in (
    REDIRECT_URI.replace("https://", "http://"),
    "https://attacker.test/login/oauth2/code/keycloak",
    f"{REDIRECT_URI}x",
    f"{REDIRECT_URI}/../../account",
    f"{REDIRECT_URI}?next=https://attacker.test",
    REDIRECT_URI.replace("https://", "https://www."),
):
    rejected, _, _ = Browser().authorize(redirect_uri=redirect)
    assert rejected.status == 400 and not rejected.location, f"redirect accepted: {redirect}"
for overrides, error in (
    ({"code_challenge": None, "code_challenge_method": None}, "invalid_request"),
    ({"code_challenge_method": "plain"}, "invalid_request"),
    ({"response_type": "token"}, "unauthorized_client"),
    ({"response_type": "code id_token"}, "unauthorized_client"),
    ({"scope": "openid offline_access"}, "invalid_scope"),
    ({"scope": "openid profile email"}, "invalid_scope"),
):
    rejected, _, _ = Browser().authorize(**overrides)
    returned = callback(rejected)
    assert returned.get("error") == error and "code" not in returned, (overrides, rejected.location)
for fields, secret, expected in (
    ({"grant_type": "password", "username": player, "password": player_password}, CLIENT_SECRET, "unauthorized_client"),
    ({"grant_type": "client_credentials"}, CLIENT_SECRET, "unauthorized_client"),
    ({"grant_type": "client_credentials"}, "not-the-client-secret", "unauthorized_client"),
):
    status, refusal = token_request(fields, secret)
    assert status in (400, 401) and refusal["error"] == expected and "access_token" not in refusal, refusal

# 11. No self-service deletion and no unverified email change.
answer_device = Browser()
login, _, _ = answer_device.authorize(kc_action="delete_account")
answer = answer_device.open(form_action(login.body, "kc-form-login"), {"username": player, "password": player_password})
assert callback(answer).get("kc_action_status") == "error", answer.location
remember_code(callback(answer)["code"])
assert account(player) is not None
console_redirect = f"{ISSUER}/account/"
console = Browser(console_redirect)
console_verifier = secrets.token_urlsafe(48)
console_login = console.open(
    f"{ISSUER}/protocol/openid-connect/auth?"
    + urllib.parse.urlencode(
        {
            "client_id": "account-console",
            "response_type": "code",
            "scope": "openid",
            "redirect_uri": console_redirect,
            "state": "account",
            "code_challenge": base64.urlsafe_b64encode(hashlib.sha256(console_verifier.encode()).digest())
            .rstrip(b"=")
            .decode(),
            "code_challenge_method": "S256",
        }
    )
)
console_answer = console.open(form_action(console_login.body, "kc-form-login"), {"username": player, "password": player_password})
console_code = urllib.parse.parse_qs(urllib.parse.urlparse(console_answer.location).query)["code"][0]
remember_code(console_code)
console_token_request = urllib.request.Request(
    f"{ISSUER}/protocol/openid-connect/token",
    urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "client_id": "account-console",
            "code": console_code,
            "redirect_uri": console_redirect,
            "code_verifier": console_verifier,
        }
    ).encode(),
)
with urllib.request.urlopen(console_token_request, context=TLS) as response:
    console_token = json.load(response)["access_token"]
remember(console_token)


def account_api(method: str, body: dict | None = None) -> tuple[int, dict | None]:
    request = urllib.request.Request(
        f"{ISSUER}/account/",
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {console_token}", "Accept": "application/json", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, context=TLS) as response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


status, own_profile = account_api("GET")
assert status == 200 and isinstance(own_profile, dict)
email_metadata = next(item for item in own_profile["userProfileMetadata"]["attributes"] if item["name"] == "email")
assert email_metadata["readOnly"] is True
changed = unique_email("changed")
account_api("POST", {**own_profile, "email": changed})
assert account_details(player)["email"] == player and account(changed) is None

# 12. #273 recovery is reused unchanged, now asking for the email identifier.
recovering = Browser()
login, _, _ = recovering.authorize()
recovery_page = recovering.open(page_link(login.body, "/login-actions/reset-credentials"))
assert "Indica tu email. Si corresponde a una cuenta" in page_text(recovery_page.body)
empty = recovering.open(form_action(recovery_page.body, "kc-reset-password-form"), {"username": ""})
assert "Indica tu email." in page_text(empty.body)
unknown = unique_email("unknown")
recovery_shapes = []
for identifier in (unknown, player):
    sent = Browser()
    login, _, _ = sent.authorize()
    page = sent.open(page_link(login.body, "/login-actions/reset-credentials"))
    answer = sent.open(form_action(page.body, "kc-reset-password-form"), {"username": identifier})
    assert RECOVERY_SENT in page_text(answer.body), "recovery responses disclose nothing"
    # Ignore only opaque per-session URLs/nonces. Compare status, navigation, text and forms,
    # not merely a hidden or restyled error. Timing is measured separately; SMTP still differs.
    recovery_shapes.append((
        answer.status,
        urllib.parse.urlsplit(answer.url).path,
        urllib.parse.urlsplit(answer.location).path,
        page_text(answer.body),
        re.findall(r'<form[^>]*\bid="([^"]+)"', answer.body),
    ))
assert recovery_shapes[0] == recovery_shapes[1], "recovery has an observable account-dependent branch"
answer = recovering.open(form_action(empty.body, "kc-reset-password-form"), {"username": player})
assert RECOVERY_SENT in page_text(answer.body)
[reset_mail, _] = messages(player, "contraseña", count=2)
assert reset_mail["Subject"] == "Crea una nueva contraseña de Gameómetro"
assert mailpit("/api/v1/search?" + urllib.parse.urlencode({"query": f'to:"{unknown}"'}))["messages_count"] == 0
password_page = recovering.open(action_link(reset_mail))
assert "Escribe tu nueva contraseña y confírmala." in page_text(password_page.body)
recovered_password = synthetic_password()
finished = choose_password(recovering, password_page, player, recovered_password)
assert "code" in callback(finished), finished.location or page_text(finished.body)[:300]
remember_code(callback(finished)["code"])
assert not callback(sign_in(player, player_password)[0])
player_password = recovered_password

# 13. A lost verification email is recovered through #273 without bypassing verification.
lost = unique_email("lost-verification")
register(Browser(), lost, {"termsAccepted": "on"})
recovering = Browser()
login, _, _ = recovering.authorize()
page = recovering.open(page_link(login.body, "/login-actions/reset-credentials"))
recovering.open(form_action(page.body, "kc-reset-password-form"), {"username": lost})
reset_page = recovering.open(action_link(messages(lost, "contraseña")[0]))
assert VERIFY_TITLE in page_text(reset_page.body) and "kc-passwd-update-form" not in reset_page.body
assert account_details(lost)["emailVerified"] is False
password_page = recovering.open(action_link(messages(lost, "Verifica tu email", count=2)[0]))
finished = choose_password(recovering, password_page, lost, synthetic_password())
assert "code" in callback(finished) and account_details(lost)["emailVerified"] is True
remember_code(callback(finished)["code"])

# 14. Five spaced failures lock one account temporarily; the lockout reads like a wrong password.
target = unique_email("lockout")
target_password = synthetic_password()
target_id = create_verified_account(target, target_password)


def lockout() -> dict:
    status, state = admin("GET", f"{ADMIN_REALM}/attack-detection/brute-force/users/{target_id}")
    assert status == 200 and isinstance(state, dict)
    return state


for attempt in range(1, 6):
    if attempt > 1:
        # Space failures after the previous asynchronous update has been observed.
        # Sleeping after only the HTTP response can let queued failures trigger quick-login.
        time.sleep(2.5)
    answer, _, _ = sign_in(target, synthetic_password())
    assert not callback(answer) and INVALID_CREDENTIALS in page_text(answer.body)
    deadline = time.monotonic() + 10
    state = lockout()
    while state["numFailures"] < attempt:
        assert time.monotonic() < deadline, "the asynchronous failure counter did not advance"
        time.sleep(0.1)
        state = lockout()
    assert state["numFailures"] == attempt, "a failure was counted more than once"
    assert state["disabled"] is (attempt == 5), (attempt, state)
answer, _, _ = sign_in(target, target_password)
assert not callback(answer) and INVALID_CREDENTIALS in page_text(answer.body), "the lockout is indistinguishable"
assert account_details(target)["enabled"] is True, "lockout is temporary, never a permanent disable"
control = callback(sign_in(player, player_password)[0])
assert "code" in control, "other accounts keep signing in"
remember_code(control["code"])
deadline = time.time() + 90
while lockout()["disabled"]:
    assert time.time() < deadline, "the temporary lockout did not expire"
    time.sleep(2)
returned = callback(sign_in(target, target_password)[0])
assert "code" in returned, "the account signs in again after the lockout"
remember_code(returned["code"])

# R17 characterization: residual disclosure is owner-accepted conditionally, not a passing
# non-enumeration claim or proof that compensatory controls are verified. Native duplicate
# validation returns the registration form whereas
# a new address reaches email verification. No theme-only change may call this resolved.
existing_registration = register(Browser(), player, {"termsAccepted": "on"})
assert "kc-register-form" in existing_registration.body
assert VERIFY_TITLE not in page_text(existing_registration.body)
EVIDENCE.joinpath("registration-enumeration.json").write_text(json.dumps({
    "enumeration_resistant": False,
    "existing_address_returns_registration_form": True,
    "new_address_reaches_verification": True,
    "risk": "R17",
    "acceptance": "conditional",
    "compensatory_controls_verified": False,
}) + "\n", encoding="utf-8")

# Bounded R17/recovery characterization: six interleaved pairs per flow, Mailpit only.
# This is a loopback observation, never a timing non-enumeration or VPS capacity claim.
risk_samples = {"registration_new": [], "registration_existing": [],
                "recovery_unknown": [], "recovery_existing": []}
risk_shapes = {}
for iteration in range(6):
    cases = [("registration_existing", player), ("registration_new", unique_email("risk")),
             ("recovery_existing", player), ("recovery_unknown", unknown)]
    if iteration % 2:
        cases.reverse()
    for kind, identifier in cases:
        browser = Browser()
        login, _, _ = browser.authorize()
        registration_case = kind.startswith("registration")
        fragment = "/login-actions/registration" if registration_case else "/login-actions/reset-credentials"
        page = browser.open(page_link(login.body, fragment))
        form = "kc-register-form" if registration_case else "kc-reset-password-form"
        fields = {"email": identifier, "termsAccepted": "on"} if registration_case else {"username": identifier}
        browser.redirects.clear()
        started = time.perf_counter()
        response = browser.open(form_action(page.body, form), fields)
        risk_samples[kind].append(round((time.perf_counter() - started) * 1000, 2))
        risk_shapes[kind] = {
            "final_status": response.status,
            "final_path": urllib.parse.urlsplit(response.url).path,
            "redirects": browser.redirects.copy(),
            "forms": re.findall(r'<form[^>]*\bid="([^"]+)"', response.body),
            "text": page_text(response.body).replace(identifier, "<submitted-email>"),
            "bytes": len(response.body.encode()),
            "cache_control": next((v for k, v in response.headers.items() if k.lower() == "cache-control"), None),
        }
summary = {kind: {"samples_ms": values, "median_ms": round(statistics.median(values), 2),
                  "min_ms": min(values), "max_ms": max(values)} for kind, values in risk_samples.items()}
EVIDENCE.joinpath("identity-risk-characterization.json").write_text(
    json.dumps({"samples_per_case": 6, "timing": summary, "responses": risk_shapes}, indent=2) + "\n",
    encoding="utf-8")
assert risk_shapes["registration_existing"]["forms"] != risk_shapes["registration_new"]["forms"]
assert risk_shapes["recovery_existing"]["final_status"] == risk_shapes["recovery_unknown"]["final_status"]
assert risk_shapes["recovery_existing"]["forms"] == risk_shapes["recovery_unknown"]["forms"]

# R18: the native global metrics listener works even with realm event storage/listeners off.
# Only internal Docker access; no product route or real SMTP is involved.
# Management inherits the main listener's TLS unless explicitly configured otherwise.
# Reuse the fixture CA: plaintext and unverified HTTPS would bypass the production proof.
identity_url = urllib.parse.urlsplit(ORIGIN)
management = os.environ.get("KEYCLOAK_TEST_MANAGEMENT_ORIGIN", f"{identity_url.scheme}://{identity_url.hostname}:9000") + "/metrics"
with urllib.request.urlopen(management, context=TLS, timeout=10) as response:
    metrics = response.read().decode()
event_samples = [line for line in metrics.splitlines() if line.startswith("keycloak_user_events_total{")]
assert event_samples, "native security event metrics are absent"
allowed_events = {"login", "register", "verify_email", "send_verify_email", "send_reset_password",
                  "reset_password", "user_disabled_by_temporary_lockout"}
for line in event_samples:
    labels = dict(re.findall(r'(\w+)="([^"]*)"', line.split("}", 1)[0]))
    assert set(labels) == {"event", "error", "realm"}, "unexpected identity metric dimension"
    assert labels["event"] in allowed_events
assert any('event="login"' in line and 'error="invalid_user_credentials"' in line for line in event_samples)
assert any('event="register"' in line and ('error="email_in_use"' in line or 'error="username_in_use"' in line)
           for line in event_samples), "duplicate registration has no security signal"
assert any('event="send_reset_password"' in line for line in event_samples)
assert not any(value in "\n".join(event_samples) for value in sensitive if len(value) > 5), "identity metrics leak data"

# 15. No user or admin events were stored.
for path in ("events", "admin-events"):
    status, stored_events = admin("GET", f"{ADMIN_REALM}/{path}")
    assert status == 200 and stored_events == [], path

EVIDENCE.joinpath("sensitive-values.txt").write_text("\n".join(sensitive) + "\n", encoding="utf-8")
EVIDENCE.joinpath("consumed-codes.txt").write_text("\n".join(consumed_codes) + "\n", encoding="utf-8")
print(
    "Production identity policy proven: declaration-gated email registration, verification before any password "
    "or sign-in, password policy, temporary lockout, strict PKCE client, no self-deletion and #273 recovery."
)
