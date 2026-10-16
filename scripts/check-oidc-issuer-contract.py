#!/usr/bin/env python3

"""Check that a running identity server and BFF agree on one canonical OIDC issuer.

Read-only and account-free, so the same check serves the disposable production-identity gate and
the real host before any account exists. It takes the BFF's own configuration values from the
environment and compares them with what Keycloak publishes, including under spoofed Host and
forwarded headers, and then with the BFF's actual authorization redirect:

  OIDC_ISSUER_URI, OIDC_AUTHORIZATION_URI, OIDC_TOKEN_URI, OIDC_JWK_SET_URI, OIDC_USER_INFO_URI,
  OIDC_END_SESSION_URI, APPLICATION_OIDC_REDIRECT_URI, APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI

Optional: OIDC_CLIENT_ID (default videogame-platform-bff), OIDC_CONTRACT_CA_FILE (extra trust
anchor for a test CA), OIDC_CONTRACT_SKIP_APPLICATION=true when no BFF is running yet.
"""

from __future__ import annotations

import base64
import hashlib
import http.client
import json
import os
import secrets
import ssl
import sys
import urllib.parse

sys.dont_write_bytecode = True

SPOOFED_HOST = "attacker.invalid"


def required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise SystemExit(f"Missing required configuration value: {name}")
    return value


ISSUER = required("OIDC_ISSUER_URI")
CONFIGURED = {
    "authorization_endpoint": required("OIDC_AUTHORIZATION_URI"),
    "token_endpoint": required("OIDC_TOKEN_URI"),
    "jwks_uri": required("OIDC_JWK_SET_URI"),
    "userinfo_endpoint": required("OIDC_USER_INFO_URI"),
    "end_session_endpoint": required("OIDC_END_SESSION_URI"),
}
REDIRECT_URI = required("APPLICATION_OIDC_REDIRECT_URI")
POST_LOGOUT_URI = required("APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI")
CLIENT_ID = os.environ.get("OIDC_CLIENT_ID", "videogame-platform-bff")
TLS = ssl.create_default_context(cafile=os.environ.get("OIDC_CONTRACT_CA_FILE") or None)
APPLICATION = urllib.parse.urlsplit(REDIRECT_URI)


def request(url: str, headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], str]:
    """One request without following redirects; Host and forwarded headers may be overridden."""
    parts = urllib.parse.urlsplit(url)
    assert parts.scheme == "https", f"{url} is not HTTPS"
    connection = http.client.HTTPSConnection(parts.hostname, parts.port or 443, context=TLS, timeout=15)
    try:
        target = parts.path + (f"?{parts.query}" if parts.query else "")
        connection.putrequest("GET", target, skip_host=True, skip_accept_encoding=True)
        merged = {"Host": parts.netloc, "Accept": "*/*", **(headers or {})}
        for name, value in merged.items():
            connection.putheader(name, value)
        connection.endheaders()
        response = connection.getresponse()
        body = response.read().decode("utf-8", errors="replace")
        return response.status, {name.lower(): value for name, value in response.getheaders()}, body
    finally:
        connection.close()


SPOOFED_HEADERS = [
    {"Host": SPOOFED_HOST},
    {"X-Forwarded-Host": SPOOFED_HOST, "X-Forwarded-Proto": "http", "X-Forwarded-Port": "8080"},
    {"Forwarded": f"host={SPOOFED_HOST};proto=http"},
    {"Host": SPOOFED_HOST, "X-Forwarded-Host": SPOOFED_HOST, "X-Forwarded-Proto": "http"},
]


def discovery(headers: dict[str, str] | None = None) -> tuple[int, dict | None]:
    """The published document, or None when the server or an edge in front of it refused."""
    status, _, body = request(f"{ISSUER}/.well-known/openid-configuration", headers)
    try:
        return status, (json.loads(body) if status == 200 else None)
    except json.JSONDecodeError:
        return status, None


# 1. Keycloak's published issuer and endpoints are exactly the BFF's configured values.
status, document = discovery()
assert status == 200 and document is not None, f"discovery answered {status}"
assert document["issuer"] == ISSUER, f"discovery issuer {document['issuer']!r} differs from OIDC_ISSUER_URI {ISSUER!r}"
for field, configured in CONFIGURED.items():
    assert document.get(field) == configured, f"discovery {field} {document.get(field)!r} differs from the BFF's {configured!r}"
print(f"Discovery publishes exactly the BFF's issuer and {len(CONFIGURED)} endpoints.")

# 2. Untrusted Host and forwarded headers change nothing: either refused, or the same document.
for headers in SPOOFED_HEADERS:
    status, spoofed = discovery(headers)
    assert spoofed is None or spoofed == document, f"discovery changed under {sorted(headers)}: {spoofed.get('issuer')!r}"
print(f"Discovery is identical or refused under {len(SPOOFED_HEADERS)} spoofed Host/forwarded header sets.")


def authorization_url(redirect_uri: str) -> str:
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return CONFIGURED["authorization_endpoint"] + "?" + urllib.parse.urlencode(
        {
            "client_id": CLIENT_ID,
            "response_type": "code",
            "scope": "openid",
            "redirect_uri": redirect_uri,
            "state": secrets.token_urlsafe(16),
            "nonce": secrets.token_urlsafe(16),
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        }
    )


def variants(uri: str) -> list[str]:
    parts = urllib.parse.urlsplit(uri)
    host = parts.netloc
    return [
        urllib.parse.urlunsplit(("http", host, parts.path, "", "")),
        urllib.parse.urlunsplit(("https", SPOOFED_HOST, parts.path, "", "")),
        urllib.parse.urlunsplit(("https", f"{host}.{SPOOFED_HOST}", parts.path, "", "")),
        urllib.parse.urlunsplit(("https", f"evil.{host}", parts.path, "", "")),
        urllib.parse.urlunsplit(("https", f"{host}:8443", parts.path, "", "")),
        urllib.parse.urlunsplit(("https", host, parts.path.rstrip("/") + "/extra", "", "")),
        urllib.parse.urlunsplit(("https", host, parts.path, "next=//attacker.invalid", "")),
    ]


def refused_without_redirect(status: int, headers: dict[str, str], target: str) -> bool:
    return status == 400 and not headers.get("location", "").startswith(target.split("?")[0])


# 3. The realm accepts exactly the BFF's callback and post-logout address, nothing near them.
status, headers, body = request(authorization_url(REDIRECT_URI))
assert status == 200 and 'id="kc-form-login"' in body, f"the configured callback was not accepted ({status})"
for variant in variants(REDIRECT_URI):
    status, headers, _ = request(authorization_url(variant))
    assert refused_without_redirect(status, headers, variant), f"callback variant {variant} answered {status}"

logout = CONFIGURED["end_session_endpoint"] + "?"
# Without a browser session there is nothing to confirm, so Keycloak returns at once.
status, headers, body = request(logout + urllib.parse.urlencode({"client_id": CLIENT_ID, "post_logout_redirect_uri": POST_LOGOUT_URI}))
assert (status == 302 and headers.get("location") == POST_LOGOUT_URI) or (status == 200 and "kc-logout-confirm" in body), (
    f"the configured post-logout address was not accepted ({status})"
)
for variant in variants(POST_LOGOUT_URI):
    query = urllib.parse.urlencode({"client_id": CLIENT_ID, "post_logout_redirect_uri": variant})
    status, headers, _ = request(logout + query)
    assert refused_without_redirect(status, headers, variant), f"post-logout variant {variant} answered {status}"
print(
    "The realm accepts only the exact callback and post-logout address;"
    f" {len(variants(REDIRECT_URI)) + len(variants(POST_LOGOUT_URI))} near variants are refused."
)

# 4. The BFF builds its authorization redirect from configuration, never from request headers;
# an edge that refuses a spoofed Host before the BFF (no redirect at all) also satisfies this.
if os.environ.get("OIDC_CONTRACT_SKIP_APPLICATION") != "true":
    entry = urllib.parse.urlunsplit(("https", APPLICATION.netloc, "/auth/login/keycloak", "", ""))
    for headers in [{}, *SPOOFED_HEADERS]:
        status, response_headers, _ = request(entry, headers)
        location = response_headers.get("location", "")
        if headers and "Host" in headers and not location and status >= 400:
            continue
        assert status == 302 and location.startswith(CONFIGURED["authorization_endpoint"] + "?"), (
            f"BFF entry under {sorted(headers)} answered {status} to {location[:80]!r}"
        )
        parameters = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
        assert parameters["redirect_uri"] == [REDIRECT_URI], f"BFF callback changed under {sorted(headers)}"
        assert parameters["code_challenge_method"] == ["S256"] and parameters["client_id"] == [CLIENT_ID]
    print(f"The BFF redirects to the canonical issuer with its fixed callback under {len(SPOOFED_HEADERS)} spoofed header sets.")

print(f"OIDC issuer contract holds for {ISSUER}.")
