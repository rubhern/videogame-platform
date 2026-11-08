#!/usr/bin/env python3
"""Open and inspect real BFF sessions for the personal-data gate, one cookie jar per device.

Runs inside the gate's internal network (scripts/validate-personal-data.sh). `login` signs in
through the BFF's normal OIDC entry and Keycloak's form, optionally creates a rating through the
product API with the session's CSRF token, and keeps the device's cookies; `check` reports
whether each device's session is still authenticated. Credentials arrive in the environment.
"""

from __future__ import annotations

import html
import http.cookiejar
import json
import os
import pathlib
import re
import sys
import urllib.parse
import urllib.request

APPLICATION = "http://application:8080"
GAME_ID = "30000000-0000-4000-8000-000000000005"
state = pathlib.Path(os.environ["SESSION_STATE_DIRECTORY"])


def opener_for(device: str, *, load: bool) -> tuple[urllib.request.OpenerDirector, http.cookiejar.LWPCookieJar]:
    jar = http.cookiejar.LWPCookieJar(state / f"{device}.cookies")
    if load:
        jar.load(ignore_discard=True)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))
    return opener, jar


def session_state(opener: urllib.request.OpenerDirector) -> dict[str, object]:
    with opener.open(f"{APPLICATION}/api/v1/session", timeout=20) as response:
        return json.loads(response.read())


def login(device: str, username: str, password: str, rating: int | None) -> None:
    opener, jar = opener_for(device, load=False)
    with opener.open(f"{APPLICATION}/auth/login/keycloak", timeout=20) as response:
        page = response.read().decode("utf-8")
    form = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', page)
    if not form:
        raise SystemExit(f"{device}: Keycloak did not show its sign-in form")
    credentials = urllib.parse.urlencode({"username": username, "password": password, "credentialId": ""}).encode()
    with opener.open(html.unescape(form.group(1)), data=credentials, timeout=20) as response:
        landing = response.geturl()
    if not landing.startswith(APPLICATION):
        raise SystemExit(f"{device}: sign-in did not return to the application")
    current = session_state(opener)
    if current.get("authenticated") is not True:
        raise SystemExit(f"{device}: the BFF session is not authenticated after sign-in")
    if rating is not None:
        request = urllib.request.Request(
            f"{APPLICATION}/api/v1/me/ratings/{GAME_ID}",
            data=json.dumps({"value": rating}).encode(),
            method="PUT",
            headers={"Content-Type": "application/json", "X-CSRF-Token": str(current["csrfToken"]), "If-None-Match": "*"},
        )
        with opener.open(request, timeout=20) as response:
            if response.status not in (200, 201):
                raise SystemExit(f"{device}: rating creation answered HTTP {response.status}")
    # #272: real OIDC principal projection and complete private export, before erasure.
    with opener.open(f"{APPLICATION}/api/v1/me/profile", timeout=20) as response:
        profile = json.loads(response.read())
        assert response.headers.get("Cache-Control") == "no-store"
        assert set(profile) <= {"name", "email", "deletionAvailable"}
    with opener.open(f"{APPLICATION}/api/v1/me/data-export", timeout=20) as response:
        exported = json.loads(response.read())
        assert response.headers.get("Cache-Control") == "no-store"
        assert exported["format"] == "gameometro-personal-data-export" and exported["version"] == 1
        assert "preferredPlatforms" in exported and isinstance(exported["ratings"], list)
        if rating is not None:
            assert any(item["gameId"] == GAME_ID and item["value"] == rating for item in exported["ratings"])
        assert not any(key in exported for key in ("password", "accessToken", "csrfToken"))
    # Existing SSO must still show credentials when the profile requests recent authentication.
    with opener.open(f"{APPLICATION}/auth/profile/reauthenticate", timeout=20) as response:
        page = response.read().decode("utf-8")
    form = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', page)
    assert form, "Profile reauthentication did not require credentials"
    with opener.open(html.unescape(form.group(1)), data=credentials, timeout=20) as response:
        assert response.geturl() == f"{APPLICATION}/mi-perfil"
    assert session_state(opener).get("authenticated") is True
    jar.save(ignore_discard=True)


def check(devices: list[str]) -> None:
    result = {}
    for device in devices:
        opener, _ = opener_for(device, load=True)
        result[device] = session_state(opener).get("authenticated") is True
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "login":
        # device:USERNAME_VARIABLE:PASSWORD_VARIABLE[:rating]
        for specification in sys.argv[2:]:
            parts = specification.split(":")
            login(parts[0], os.environ[parts[1]], os.environ[parts[2]], int(parts[3]) if len(parts) > 3 else None)
    elif command in ("request-deletion", "deletion-status"):
        opener, jar = opener_for(sys.argv[2], load=True)
        if command == "request-deletion":
            current = session_state(opener)
            request = urllib.request.Request(f"{APPLICATION}/api/v1/me/account-deletion", method="POST",
                data=json.dumps({"confirmation":"ELIMINAR"}).encode(),
                headers={"Content-Type":"application/json", "X-CSRF-Token":current["csrfToken"]})
        else:
            request = urllib.request.Request(f"{APPLICATION}/api/v1/account-deletion")
        with opener.open(request, timeout=20) as response:
            print(response.read().decode())
        if command == "request-deletion":
            assert session_state(opener).get("authenticated") is False, "BFF session must end on acceptance"
        jar.save(ignore_discard=True)
    elif command == "check":
        check(sys.argv[2:])
    else:
        raise SystemExit("usage: login device:USER_VAR:PASSWORD_VAR[:rating]... | check device...")
