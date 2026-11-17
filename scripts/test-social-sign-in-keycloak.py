#!/usr/bin/env python3
"""Prove the reviewed social sign-in shape (#548, ADR-0045) against a disposable Keycloak.

Run by scripts/validate-social-sign-in.sh. Google and Microsoft cannot be reached from a test, so
two OIDC providers backed by a second disposable realm stand in for them: one trusts the provider's
email, like Google, and one does not, like Microsoft. Both use the reviewed first-broker-login flow
that configure-social-sign-in installs; the real google and microsoft entries are checked as
configuration and as buttons. Each Browser is one device; a returned authorization code at the BFF
callback is the only evidence that Keycloak let an account in.
"""
from __future__ import annotations

import base64
import hashlib
import html
import html.parser
import http.cookiejar
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
ROOT = pathlib.Path(__file__).resolve().parents[1]
ACTOR = ROOT / "deploy/private-dev/bin/configure-social-sign-in"


def required_environment(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise AssertionError(f"Missing required integration-test environment variable: {name}")
    return value


ORIGIN = required_environment("KEYCLOAK_TEST_ORIGIN")
ADMIN_USERNAME = required_environment("KEYCLOAK_TEST_ADMIN_USERNAME")
ADMIN_PASSWORD = required_environment("KEYCLOAK_TEST_ADMIN_PASSWORD")
MAILPIT = required_environment("MAILPIT_API_ORIGIN")
MAIL_HOST = required_environment("MAIL_TEST_HOST")

REALM = "gameometro"
PROVIDER_REALM = "stand-in-provider"
CLIENT_ID = "videogame-platform-bff"
APPLICATION = "https://gameometro.test"
REDIRECT_URI = f"{APPLICATION}/login/oauth2/code/keycloak"
ISSUER = f"{ORIGIN}/realms/{REALM}"
INTERNAL = "http://localhost:8080"  # Keycloak calling itself from inside its container
TRUSTED, UNTRUSTED = "stand-in-trusted", "stand-in-untrusted"
FLOW = "gameometro first broker login"


class Response(NamedTuple):
    status: int
    url: str
    location: str
    body: str


class Forms(html.parser.HTMLParser):
    """Every form on a page: its id, action and the named inputs it would submit."""

    def __init__(self, body: str) -> None:
        super().__init__()
        self.forms: list[dict] = []
        self.feed(body)

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "form":
            self.forms.append({"id": attributes.get("id"), "action": html.unescape(attributes.get("action") or ""), "fields": {}, "buttons": {}})
        elif self.forms and tag in ("input", "button") and attributes.get("name"):
            kind = attributes.get("type", "submit" if tag == "button" else "text")
            target = self.forms[-1]["buttons" if kind == "submit" else "fields"]
            target[attributes["name"]] = attributes.get("value") or ""


def page_text(body: str) -> str:
    return html.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", body))).strip()


class LoopbackCookies(http.cookiejar.DefaultCookiePolicy):
    """Keycloak marks its cookies Secure; a browser also returns them to plain-HTTP loopback."""

    def return_ok_secure(self, cookie, request):
        return True


class Browser:
    """One device: its own cookies, stopping when a redirect reaches the BFF callback."""

    def __init__(self) -> None:
        browser = self

        class StopAtApplication(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, msg, headers, new_url):  # noqa: N802
                if new_url.startswith(APPLICATION):
                    return None
                return super().redirect_request(request, fp, code, msg, headers, new_url)

        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                  urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(LoopbackCookies())),
                                                  StopAtApplication())

    def open(self, url: str, fields: dict[str, str] | None = None) -> Response:
        data = urllib.parse.urlencode(fields).encode() if fields is not None else None
        try:
            with self.opener.open(url, data, timeout=20) as response:
                return Response(response.status, response.url, "", response.read().decode())
        except urllib.error.HTTPError as error:
            return Response(error.code, url, error.headers.get("Location") or "", error.read().decode(errors="replace"))

    def authorize(self) -> Response:
        query = urllib.parse.urlencode({"client_id": CLIENT_ID, "response_type": "code", "scope": "openid",
                                        "redirect_uri": REDIRECT_URI, "state": secrets.token_urlsafe(16),
                                        "nonce": secrets.token_urlsafe(16), "code_challenge_method": "S256",
                                        "code_challenge": base64.urlsafe_b64encode(hashlib.sha256(
                                            secrets.token_bytes(32)).digest()).rstrip(b"=").decode()})
        return self.open(f"{ISSUER}/protocol/openid-connect/auth?{query}")

    def submit(self, page: Response, button: str | None = None, form_id: str | None = None, **values: str) -> Response:
        forms = [form for form in Forms(page.body).forms if form_id is None or form["id"] == form_id]
        assert forms, f"No form {form_id or ''} on: {page_text(page.body)[:300]}"
        form = forms[0]
        fields = {**form["fields"], **values}
        if button:
            name, _, value = button.partition("=")
            assert name in form["buttons"], f"No {name} button on: {page_text(page.body)[:300]}"
            fields[name] = value or form["buttons"][name]
        return self.open(urllib.parse.urljoin(page.url, form["action"]), fields)


def code_of(response: Response) -> str | None:
    if response.status not in (302, 303) or not response.location.startswith(REDIRECT_URI):
        return None
    return urllib.parse.parse_qs(urllib.parse.urlsplit(response.location).query).get("code", [None])[0]


# --- administration ---
def token() -> str:
    data = urllib.parse.urlencode({"client_id": "admin-cli", "grant_type": "password",
                                   "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}).encode()
    with urllib.request.urlopen(f"{ORIGIN}/realms/master/protocol/openid-connect/token", data, timeout=20) as response:
        return json.load(response)["access_token"]


def admin(method: str, path: str, body: object | None = None) -> tuple[int, object]:
    request = urllib.request.Request(f"{ORIGIN}/admin/realms{path}", method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")


def ok(method: str, path: str, body: object | None = None) -> object:
    status, payload = admin(method, path, body)
    assert status in (200, 201, 204), f"{method} {path} -> {status}: {payload}"
    return payload


def actor(*arguments: str, providers: dict) -> dict:
    document = json.dumps({"adminPassword": ADMIN_PASSWORD, "providers": providers})
    result = subprocess.run([sys.executable, "-I", str(ACTOR), "--origin", ORIGIN, "--realm", REALM,
                             "--admin-username", ADMIN_USERNAME, *arguments],
                            input=document, capture_output=True, text=True, timeout=120)
    assert result.returncode == 0, result.stderr
    assert "secret" not in result.stdout.lower().replace("clientsecret", ""), "the actor printed a credential"
    return json.loads(result.stdout)


def user_by_email(email: str) -> dict | None:
    users = ok("GET", f"/{REALM}/users?" + urllib.parse.urlencode({"email": email, "exact": "true", "briefRepresentation": "false"}))
    assert len(users) <= 1
    return users[0] if users else None


def links(user_id: str) -> set[str]:
    return {link["identityProvider"] for link in ok("GET", f"/{REALM}/users/{user_id}/federated-identity")}


# --- fixtures ---
def prepare() -> None:
    # Test-only: plain HTTP on loopback and an unauthenticated mail sink. Nothing else differs from the import.
    realm = ok("GET", f"/{REALM}")
    ok("PUT", f"/{REALM}", {"sslRequired": "none", "smtpServer": {**realm["smtpServer"], "host": MAIL_HOST, "port": "1025",
                                                                   "auth": "false", "starttls": "false", "user": "", "password": ""}})
    ok("POST", "", {"realm": PROVIDER_REALM, "enabled": True, "sslRequired": "none"})
    ok("POST", f"/{PROVIDER_REALM}/clients", {
        "clientId": "gameometro-broker", "secret": "stand-in-secret", "publicClient": False, "standardFlowEnabled": True,
        "directAccessGrantsEnabled": False, "redirectUris": [f"{ISSUER}/broker/*"]})


def stand_in_user(email: str) -> str:
    password = secrets.token_urlsafe(18)
    ok("POST", f"/{PROVIDER_REALM}/users", {"username": email, "email": email, "emailVerified": True, "enabled": True,
                                            "firstName": "Stand", "lastName": "In",
                                            "credentials": [{"type": "password", "value": password, "temporary": False}]})
    return password


def stand_in_provider(alias: str, trust_email: bool) -> None:
    # The untrusted stand-in also sends names, as Microsoft Graph does; the profile must not keep them.
    scope = "openid email" if trust_email else "openid email profile"
    base = f"/realms/{PROVIDER_REALM}/protocol/openid-connect"
    ok("POST", f"/{REALM}/identity-provider/instances", {
        "alias": alias, "providerId": "oidc", "enabled": True, "trustEmail": trust_email, "storeToken": False,
        "firstBrokerLoginFlowAlias": FLOW, "config": {
            "clientId": "gameometro-broker", "clientSecret": "stand-in-secret", "clientAuthMethod": "client_secret_post",
            "authorizationUrl": ORIGIN + base + "/auth", "tokenUrl": INTERNAL + base + "/token",
            "userInfoUrl": INTERNAL + base + "/userinfo", "jwksUrl": INTERNAL + base + "/certs", "useJwksUrl": "true",
            "validateSignature": "true", "issuer": f"{ORIGIN}/realms/{PROVIDER_REALM}", "defaultScope": scope,
            "syncMode": "IMPORT", "pkceEnabled": "true", "pkceMethod": "S256"}})


def through_provider(browser: Browser, alias: str, email: str, password: str) -> Response:
    """From the Gameómetro sign-in page through the stand-in provider and back to Keycloak."""
    login = browser.authorize()
    match = re.search(rf'id="social-{alias}"[^>]*href="([^"]+)"', login.body)
    assert match, f"no {alias} button on the sign-in page"
    provider_login = browser.open(urllib.parse.urljoin(login.url, html.unescape(match.group(1))))
    return browser.submit(provider_login, form_id="kc-form-login", username=email, password=password)


def mail_count(address: str) -> int:
    query = urllib.parse.urlencode({"query": f"to:{address}"})
    with urllib.request.urlopen(f"{MAILPIT}/api/v1/search?{query}", timeout=20) as response:
        return json.load(response)["messages_count"]


# --- journeys ---
def buttons_follow_the_configuration() -> None:
    fake = {"google": {"clientId": "test-client.apps.googleusercontent.com", "clientSecret": "not-a-real-secret"},
            "microsoft": {"clientId": "00000000-0000-0000-0000-000000000000", "clientSecret": "not-a-real-secret"}}
    first = actor(providers=fake)
    assert first["providers"] == {"google": "enabled", "microsoft": "enabled"}, first
    assert actor("--check", providers=fake)["changed"] == []
    browser = Browser()
    login = browser.authorize()
    for alias, label in (("google", "Google"), ("microsoft", "Microsoft")):
        assert re.search(rf'id="social-{alias}"', login.body), f"{alias} missing from sign-in"
        assert f"Continuar con {label}" in page_text(login.body)
    assert "o continúa con" in page_text(login.body).lower(), page_text(login.body)
    register_link = re.search(r'href="([^"]*login-actions/registration\?[^"]*)"', login.body)
    assert register_link, "no registration link"
    register = browser.open(urllib.parse.urljoin(login.url, html.unescape(register_link.group(1))))
    assert 'id="social-google"' in register.body and 'id="social-microsoft"' in register.body, "registration lacks the providers"
    google = ok("GET", f"/{REALM}/identity-provider/instances/google")
    assert google["trustEmail"] is True and google["config"]["defaultScope"] == "openid email"
    assert google["firstBrokerLoginFlowAlias"] == FLOW and google["config"]["syncMode"] == "IMPORT"
    microsoft = ok("GET", f"/{REALM}/identity-provider/instances/microsoft")
    assert microsoft["trustEmail"] is False and microsoft["config"]["tenantId"] == "consumers"
    terms = next(a for a in ok("GET", f"/{REALM}/authentication/required-actions") if a["alias"] == "TERMS_AND_CONDITIONS")
    assert terms["enabled"] is True and terms["defaultAction"] is False, "email registrants must not see the terms twice"
    # Disabling hides both buttons and keeps the providers, so their linked identities survive.
    actor("--disable", providers={})
    hidden = Browser().authorize()
    assert 'id="social-google"' not in hidden.body and 'id="social-microsoft"' not in hidden.body
    assert {"google", "microsoft"} <= {p["alias"] for p in ok("GET", f"/{REALM}/identity-provider/instances")}
    print("PASS buttons, provider configuration, terms action and disabling follow the reviewed shape")


def new_account_accepts_terms_once() -> str:
    email = f"new-{secrets.token_hex(4)}@example.org"
    password = stand_in_user(email)
    browser = Browser()
    terms = through_provider(browser, TRUSTED, email, password)
    assert code_of(terms) is None, "a new provider account signed in before accepting the terms"
    assert "Antes de crear tu cuenta" in page_text(terms.body), page_text(terms.body)[:400]
    declined = browser.submit(terms, button="cancel")
    assert code_of(declined) is None, "declining the terms still signed in"
    # Declining returns to the BFF as a cancellation, which it already handles as a safe anonymous return.
    assert "error=access_denied" in declined.location, declined.location
    user = user_by_email(email)
    assert user and "TERMS_AND_CONDITIONS" in user["requiredActions"]
    again = Browser()
    terms = through_provider(again, TRUSTED, email, password)
    assert "Antes de crear tu cuenta" in page_text(terms.body), "the terms were not asked again after a refusal"
    accepted = again.submit(terms, button="accept")
    assert code_of(accepted), page_text(accepted.body)[:400]
    user = user_by_email(email)
    assert user["emailVerified"] is True, "the trusted provider's verified email was not kept"
    assert user["attributes"].get("terms_and_conditions"), "the acceptance was not recorded"
    assert links(user["id"]) == {TRUSTED}
    returning = through_provider(Browser(), TRUSTED, email, password)
    assert code_of(returning), "a returning provider account was asked again"
    assert user_by_email(email)["id"] == user["id"], "a returning provider account changed its Keycloak subject"
    print("PASS a new provider account accepts the terms once and keeps one subject on return")
    return user["id"]


def existing_account_links_only_after_its_password() -> None:
    email = f"existing-{secrets.token_hex(4)}@example.org"
    local_password = secrets.token_urlsafe(18)
    ok("POST", f"/{REALM}/users", {"username": email, "email": email, "emailVerified": True, "enabled": True,
                                   "credentials": [{"type": "password", "value": local_password, "temporary": False}]})
    subject = user_by_email(email)["id"]
    provider_password = stand_in_user(email)
    for alias in (TRUSTED, UNTRUSTED):
        browser = Browser()
        confirm = through_provider(browser, alias, email, provider_password)
        assert code_of(confirm) is None, f"{alias}: a matching email signed in without proof"
        assert alias not in links(subject), f"{alias}: linked by matching email alone"
        assert "Ya existe una cuenta" in page_text(confirm.body), page_text(confirm.body)[:400]
        reauthenticate = browser.submit(confirm, button="submitAction=linkAccount", form_id="kc-register-form")
        wrong = browser.submit(reauthenticate, form_id="kc-form-login", password="not-the-password-123")
        assert code_of(wrong) is None and alias not in links(subject), f"{alias}: a wrong password linked the provider"
        linked = browser.submit(wrong, form_id="kc-form-login", password=local_password)
        assert code_of(linked), f"{alias}: the correct password did not link: {page_text(linked.body)[:400]}"
    assert links(subject) == {TRUSTED, UNTRUSTED}, links(subject)
    user = user_by_email(email)
    assert user["id"] == subject, "linking replaced the existing subject and would orphan its ratings"
    assert "TERMS_AND_CONDITIONS" not in user["requiredActions"], "an existing account was asked to accept again"
    for alias in (TRUSTED, UNTRUSTED):
        assert code_of(through_provider(Browser(), alias, email, provider_password)), f"{alias}: the link did not persist"
    print("PASS an existing account links each provider only after its own password and keeps its subject")


def provider_only_account_links_through_its_provider() -> None:
    """An account created with one provider has no password; the other provider links through it."""
    email = f"provider-only-{secrets.token_hex(4)}@example.org"
    password = stand_in_user(email)
    first = Browser()
    accepted = first.submit(through_provider(first, TRUSTED, email, password), button="accept")
    assert code_of(accepted), page_text(accepted.body)[:400]
    subject = user_by_email(email)["id"]
    second = Browser()
    confirm = through_provider(second, UNTRUSTED, email, password)
    assert UNTRUSTED not in links(subject), "linked by matching email alone"
    reauthenticate = second.submit(confirm, button="submitAction=linkAccount", form_id="kc-register-form")
    match = re.search(rf'id="social-{TRUSTED}"[^>]*href="([^"]+)"', reauthenticate.body)
    assert match, "the account's own provider is not offered to prove ownership"
    provider_login = second.open(urllib.parse.urljoin(reauthenticate.url, html.unescape(match.group(1))))
    # Both stand-ins share one provider realm, whose session from the first step signs straight in.
    linked = provider_login if code_of(provider_login) else second.submit(
        provider_login, form_id="kc-form-login", username=email, password=password)
    assert code_of(linked), page_text(linked.body)[:400]
    assert links(subject) == {TRUSTED, UNTRUSTED} and user_by_email(email)["id"] == subject
    print("PASS an account without a password links another provider only through its own provider")


def untrusted_email_is_verified_once() -> None:
    email = f"unverified-{secrets.token_hex(4)}@example.org"
    password = stand_in_user(email)
    browser = Browser()
    terms = through_provider(browser, UNTRUSTED, email, password)
    assert "Antes de crear tu cuenta" in page_text(terms.body), page_text(terms.body)[:400]
    verify = browser.submit(terms, button="accept")
    assert code_of(verify) is None, "an untrusted provider email signed in unverified"
    assert "Verifica tu email" in page_text(verify.body), page_text(verify.body)[:400]
    user = user_by_email(email)
    assert user["emailVerified"] is False and "VERIFY_EMAIL" in user["requiredActions"]
    assert not user.get("firstName") and not user.get("lastName"), "the provider's names were stored"
    deadline = time.monotonic() + 30
    while mail_count(email) == 0:
        assert time.monotonic() < deadline, "no verification email reached the sink"
        time.sleep(1)
    print("PASS an untrusted provider email must be verified before the first session")


def provider_cancellation_returns_to_sign_in() -> None:
    browser = Browser()
    login = browser.authorize()
    start = html.unescape(re.search(rf'id="social-{TRUSTED}"[^>]*href="([^"]+)"', login.body).group(1))
    provider_page = browser.open(urllib.parse.urljoin(login.url, start))
    state = urllib.parse.parse_qs(urllib.parse.urlsplit(provider_page.url).query)["state"][0]
    # What the provider sends back when the person cancels its consent or sign-in.
    cancelled = browser.open(f"{ISSUER}/broker/{TRUSTED}/endpoint?" + urllib.parse.urlencode({"error": "access_denied", "state": state}))
    assert code_of(cancelled) is None
    assert "Has cancelado el acceso con" in page_text(cancelled.body), page_text(cancelled.body)[:400]
    assert 'id="kc-form-login"' in cancelled.body, "a cancelled provider must leave email and password available"
    print("PASS a cancelled provider returns to the Spanish sign-in with email and password still available")


def main() -> None:
    prepare()
    buttons_follow_the_configuration()
    stand_in_provider(TRUSTED, True)
    stand_in_provider(UNTRUSTED, False)
    new_account_accepts_terms_once()
    existing_account_links_only_after_its_password()
    provider_only_account_links_through_its_provider()
    untrusted_email_is_verified_once()
    provider_cancellation_returns_to_sign_in()
    print("Social sign-in journeys passed.")


if __name__ == "__main__":
    main()
