"""Edge stub and probe for the public-production Caddy contract (#275).

Runs inside disposable containers started by test-public-production-runtime.py:

  serve <tag>     Upstream stand-in that echoes the request it received as JSON.
  probe           Client on a network outside the runtime: routes, forwarding headers,
                  host/TLS refusal, protocol and size bounds. Prints each violation.
  backchannel     Client on the application edge network: the BFF's path to the issuer.
  limit <scenario> [arguments]
                  One request-limit or maintenance scenario from one source address; the
                  contract test runs them in order from differently addressed clients.
                  EDGE_ADDRESS, when set, is the edge address to connect to (IPv6 clients).

Exit status 1 means at least one violation. No real host name resolves outside Docker.
"""

import http.client
import http.server
import json
import os
import socket
import ssl
import sys
import time

APPLICATION_HOST = "gameometro.com"
IDENTITY_HOST = "auth.gameometro.com"
SPOOFED_ADDRESS = "203.0.113.9"
SPOOFED_HEADERS = {
    "X-Forwarded-For": SPOOFED_ADDRESS,
    "X-Forwarded-Proto": "http",
    "X-Forwarded-Host": "attacker.invalid",
    "X-Forwarded-Port": "8443",
    "X-Forwarded-Prefix": "/attacker",
    "X-Forwarded-Server": "attacker.invalid",
    "X-Real-Ip": SPOOFED_ADDRESS,
    "Forwarded": f"for={SPOOFED_ADDRESS};proto=http;host=attacker.invalid",
}
STRIPPED_HEADERS = ("forwarded", "x-forwarded-port", "x-forwarded-prefix", "x-forwarded-server", "x-real-ip")

IDENTITY_ROUTED = (
    "/realms/gameometro/.well-known/openid-configuration",
    "/realms/gameometro/protocol/openid-connect/auth?client_id=videogame-platform-bff",
    "/realms/gameometro/protocol/openid-connect/token",
    "/realms/gameometro/protocol/openid-connect/logout",
    "/realms/gameometro/login-actions/authenticate",
    "/realms/gameometro/login-actions/action-token?key=synthetic",
    "/resources/abc12/login/gameometro/css/login.css",
)
IDENTITY_REFUSED = (
    "/",
    "/admin",
    "/admin/",
    "/admin/master/console/",
    "/admin/realms/gameometro/users",
    "/ADMIN/master/console/",
    "/realms/master/protocol/openid-connect/token",
    "/realms/master/.well-known/openid-configuration",
    "/realms/gameometro",
    "/realms/gameometro/",
    "/realms/gameometro/account",
    "/realms/gameometro/account/",
    "/health/ready",
    "/metrics",
    "/realms/gameometro/protocol/openid-connect/../../../master/protocol/openid-connect/token",
    "/realms/gameometro/login-actions/../../master/protocol/openid-connect/token",
    "/resources/../admin/master/console/",
    "/resources/%2e%2e/admin/master/console/",
    "/resources/..%2fadmin%2fmaster%2fconsole%2f",
    "/realms/gameometro/protocol/openid-connect/%2e%2e/%2e%2e/%2e%2e/master/protocol/openid-connect/token",
    # Keycloak reaches the same endpoint through these, past the exact per-path limits.
    "/realms/gameometro/login-actions/registration;x=1",
    "/realms/gameometro/login-actions/registration%3Bx=1",
    "/realms/gameometro/login-actions/registration/",
    "/realms/gameometro/login-actions/reset-credentials;x",
    "/realms/gameometro/login-actions/authenticate/",
    "/realms/gameometro/protocol/openid-connect/token;x",
    "/realms/gameometro/login-actions/required-action;x?execution=VERIFY_EMAIL",
)
APPLICATION_REFUSED = (
    "/actuator",
    "/actuator/health",
    "/actuator/sessionrevocation",
    "/ACTUATOR/health",
    "/%61ctuator/health",
    "/api/../actuator/health",
    "/api/%2e%2e/actuator/health",
)


class Echo(http.server.BaseHTTPRequestHandler):
    server_version = "edge-stub"
    sys_version = ""
    tag = "unknown"

    def _echo(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        headers = {}
        for name, value in self.headers.items():
            headers.setdefault(name.lower(), []).append(value)
        body = json.dumps({"upstream": self.tag, "path": self.path, "headers": headers}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_HEAD = do_PUT = do_DELETE = _echo

    def log_message(self, *_):
        pass


def serve(tag):
    Echo.tag = tag
    http.server.ThreadingHTTPServer(("0.0.0.0", 8080), Echo).serve_forever()


def tls_context():
    # Routing, not certificate trust, is under test: the CA is Caddy's disposable internal one.
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


class EdgeConnection(http.client.HTTPSConnection):
    """TLS for the server name, to EDGE_ADDRESS when set (an IPv6 literal), else by name."""

    def connect(self):
        address = os.environ.get("EDGE_ADDRESS") or self.host
        raw = socket.create_connection((address, self.port), self.timeout)
        self.sock = self._context.wrap_socket(raw, server_hostname=self.host)


def exchange(server_name, path, host=None, method="GET", headers=None, body=None):
    """Return (status, lower-cased response headers, body bytes); raise on transport failure."""
    connection = EdgeConnection(server_name, 443, timeout=10, context=tls_context())
    try:
        connection.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
        connection.putheader("Host", host or server_name)
        for name, value in (headers or {}).items():
            connection.putheader(name, value)
        if body is not None:
            connection.putheader("Content-Length", str(len(body)))
        connection.endheaders(body)
        response = connection.getresponse()
        return response.status, {k.lower(): v for k, v in response.getheaders()}, response.read()
    finally:
        connection.close()


def request(server_name, path, host=None, method="GET", headers=None, body=None):
    """Return (status, response headers, echoed upstream or None); raise on transport failure."""
    status, response_headers, payload = exchange(server_name, path, host, method, headers, body)
    upstream = None
    if response_headers.get("content-type") == "application/json":
        try:
            upstream = json.loads(payload)
        except ValueError:
            upstream = None
    return status, response_headers, upstream


def plain_request(host, path):
    connection = http.client.HTTPConnection(APPLICATION_HOST, 80, timeout=10)
    try:
        connection.putrequest("GET", path, skip_host=True, skip_accept_encoding=True)
        connection.putheader("Host", host)
        connection.endheaders()
        response = connection.getresponse()
        response.read()
        return response.status, response.getheader("Location")
    finally:
        connection.close()


def handshake(server_name):
    """Return the negotiated ALPN protocol, or raise when the edge refuses the handshake."""
    context = tls_context()
    context.set_alpn_protocols(["h2", "http/1.1"])
    with socket.create_connection((APPLICATION_HOST, 443), timeout=10) as raw:
        with context.wrap_socket(raw, server_hostname=server_name) as secured:
            return secured.selected_alpn_protocol()


class Probe:
    def __init__(self):
        self.violations = []
        self.checks = 0

    def expect(self, name, condition, detail=""):
        self.checks += 1
        if not condition:
            self.violations.append(f"{name}: {detail}".rstrip(": "))

    def routed(self, name, server_name, path, upstream_tag, headers=None):
        try:
            status, response_headers, upstream = request(server_name, path, headers=headers)
        except (OSError, http.client.HTTPException) as error:
            self.expect(name, False, f"transport failure {type(error).__name__}")
            return None
        reached = upstream.get("upstream") if upstream else None
        self.expect(name, status == 200 and reached == upstream_tag, f"{path} -> {status}, upstream {reached}")
        self.expect(f"{name} hides Server", "server" not in response_headers, response_headers.get("server", ""))
        self.expect(f"{name} advertises no HTTP/3", "alt-svc" not in response_headers, response_headers.get("alt-svc", ""))
        return upstream

    def refused(self, name, server_name, path, host=None, status_expected=404):
        try:
            status, _, upstream = request(server_name, path, host=host)
        except (OSError, http.client.HTTPException) as error:
            # A closed connection also refuses, but the contract names an explicit status.
            self.expect(name, False, f"{path} transport failure {type(error).__name__}")
            return
        self.expect(name, status == status_expected and upstream is None,
                    f"{path} -> {status}, upstream {upstream.get('upstream') if upstream else None}")

    def forwarding(self, name, upstream, host):
        if upstream is None:
            self.expect(name, False, "no upstream echo")
            return
        headers = upstream["headers"]
        forwarded_for = headers.get("x-forwarded-for", [])
        self.expect(f"{name} X-Forwarded-For", len(forwarded_for) == 1 and SPOOFED_ADDRESS not in forwarded_for[0]
                    and "," not in forwarded_for[0], str(forwarded_for))
        self.expect(f"{name} X-Forwarded-Proto", headers.get("x-forwarded-proto") == ["https"],
                    str(headers.get("x-forwarded-proto")))
        self.expect(f"{name} X-Forwarded-Host", headers.get("x-forwarded-host") == [host],
                    str(headers.get("x-forwarded-host")))
        self.expect(f"{name} Host", headers.get("host") == [host], str(headers.get("host")))
        present = [header for header in STRIPPED_HEADERS if header in headers]
        self.expect(f"{name} strips client forwarding headers", not present, ", ".join(present))


def probe():
    check = Probe()
    upstream = check.routed("application route", APPLICATION_HOST, "/", "application")
    check.routed("application API route", APPLICATION_HOST, "/api/v1/session", "application")
    upstream = check.routed("application spoofed forwarding", APPLICATION_HOST, "/api/v1/session", "application",
                            SPOOFED_HEADERS)
    check.forwarding("application forwarding", upstream, APPLICATION_HOST)
    for path in APPLICATION_REFUSED:
        check.refused("application management refused", APPLICATION_HOST, path)

    for path in IDENTITY_ROUTED:
        check.routed("identity route", IDENTITY_HOST, path, "keycloak")
    upstream = check.routed("identity spoofed forwarding", IDENTITY_HOST,
                            "/realms/gameometro/protocol/openid-connect/auth", "keycloak", SPOOFED_HEADERS)
    check.forwarding("identity forwarding", upstream, IDENTITY_HOST)
    for path in IDENTITY_REFUSED:
        check.refused("identity path refused", IDENTITY_HOST, path)

    check.refused("TLS name and Host must match", APPLICATION_HOST, "/", host=IDENTITY_HOST, status_expected=421)
    check.refused("TLS name and Host must match", IDENTITY_HOST,
                  "/realms/gameometro/.well-known/openid-configuration", host=APPLICATION_HOST, status_expected=421)
    for server_name in ("unknown.gameometro.com", "attacker.invalid", None):
        try:
            handshake(server_name)
            check.expect("unknown TLS name refused", False, f"handshake for {server_name} succeeded")
        except (OSError, ssl.SSLError):
            check.expect("unknown TLS name refused", True)
    try:
        check.expect("HTTP/2 offered over TLS", handshake(APPLICATION_HOST) == "h2")
    except (OSError, ssl.SSLError) as error:
        check.expect("HTTP/2 offered over TLS", False, type(error).__name__)

    for host in (APPLICATION_HOST, IDENTITY_HOST):
        try:
            status, location = plain_request(host, "/path?query=1")
            check.expect("plain HTTP redirects to HTTPS", status in (301, 308) and location == f"https://{host}/path?query=1",
                         f"{host} -> {status} {location}")
        except (OSError, http.client.HTTPException) as error:
            check.expect("plain HTTP redirects to HTTPS", False, f"{host} {type(error).__name__}")
    try:
        status, _ = plain_request("attacker.invalid", "/")
        check.expect("unknown plain HTTP host refused", False, f"answered {status}")
    except (OSError, http.client.HTTPException):
        check.expect("unknown plain HTTP host refused", True)

    try:
        status, _, upstream = request(APPLICATION_HOST, "/api/v1/ratings", method="POST", body=b"x" * 1_100_000)
        check.expect("oversized body refused", status == 413 and upstream is None, f"-> {status}")
    except (OSError, http.client.HTTPException):
        # Refusing by closing the connection is equally acceptable for an oversized body.
        check.expect("oversized body refused", True)
    try:
        status, _, upstream = request(APPLICATION_HOST, "/", headers={"X-Oversized": "x" * 40_000})
        check.expect("oversized headers refused", status == 431 and upstream is None, f"-> {status}")
    except (OSError, http.client.HTTPException):
        check.expect("oversized headers refused", True)
    return check


def backchannel():
    check = Probe()
    check.routed("BFF reaches the issuer through the edge", IDENTITY_HOST,
                 "/realms/gameometro/protocol/openid-connect/token", "keycloak")
    check.refused("BFF cannot reach identity administration", IDENTITY_HOST, "/admin/master/console/")
    return check


# --- Request limits and maintenance -------------------------------------------------

REALM = "/realms/gameometro"
RATING = "/api/v1/me/ratings/00000000-0000-4000-8000-000000000001"
# One representative request per limited class: (method, host, path, body).
KINDS = {
    "shell": ("GET", APPLICATION_HOST, "/", None),
    "asset": ("GET", APPLICATION_HOST, "/assets/index-synthetic.js", None),
    "navigation": ("GET", APPLICATION_HOST, "/auth/start", None),
    "callback": ("GET", APPLICATION_HOST, "/login/oauth2/code/keycloak?code=synthetic&state=synthetic", None),
    "read": ("GET", APPLICATION_HOST, "/api/v1/platforms", None),
    "session": ("GET", APPLICATION_HOST, "/api/v1/session", None),
    "search": ("GET", APPLICATION_HOST, "/api/v1/games?q=synthetic", None),
    "releases": ("GET", APPLICATION_HOST, "/api/v1/releases", None),
    "write": ("PUT", APPLICATION_HOST, RATING, b'{"value":8}'),
    "identity-page": ("GET", IDENTITY_HOST, REALM + "/protocol/openid-connect/auth?client_id=videogame-platform-bff", None),
    "identity-resource": ("GET", IDENTITY_HOST, "/resources/abc12/login/gameometro/css/login.css", None),
    "login": ("POST", IDENTITY_HOST, REALM + "/login-actions/authenticate", b"username=synthetic"),
    "registration": ("POST", IDENTITY_HOST, REALM + "/login-actions/registration", b"email=synthetic"),
    # Keycloak serves this as the registration endpoint; the limit must count it the same way.
    "registration-slashes": ("POST", IDENTITY_HOST, "/realms/gameometro/login-actions//registration", b"email=synthetic"),
    "recovery": ("POST", IDENTITY_HOST, REALM + "/login-actions/reset-credentials", b"username=synthetic"),
    "verification": ("GET", IDENTITY_HOST, REALM + "/login-actions/required-action?execution=VERIFY_EMAIL&client_id=videogame-platform-bff", None),
    "required-action": ("POST", IDENTITY_HOST, REALM + "/login-actions/required-action?execution=UPDATE_PASSWORD", b"password-new=synthetic"),
    "action-link": ("GET", IDENTITY_HOST, REALM + "/login-actions/action-token?key=synthetic", None),
    "token": ("POST", IDENTITY_HOST, REALM + "/protocol/openid-connect/token", b"grant_type=authorization_code"),
    "userinfo": ("GET", IDENTITY_HOST, REALM + "/protocol/openid-connect/userinfo", None),
    "certs": ("GET", IDENTITY_HOST, REALM + "/protocol/openid-connect/certs", None),
}
# Every header a client might use to claim another address; none may choose the limit key.
SPOOFS = {
    "xff": {"X-Forwarded-For": "{target}"},
    "xff-chain": {"X-Forwarded-For": "{target}, 198.51.100.7"},
    "real-ip": {"X-Real-Ip": "{target}"},
    "forwarded": {"Forwarded": "for={target}"},
    "client-ip": {"Client-Ip": "{target}", "X-Client-Ip": "{target}"},
    "true-client-ip": {"True-Client-Ip": "{target}", "Cf-Connecting-Ip": "{target}"},
    "cluster-client-ip": {"X-Cluster-Client-Ip": "{target}", "Fastly-Client-Ip": "{target}"},
}
EXPECTED = ("ok", "limited", "overloaded", "maintenance", "aborted")


class Limits(Probe):
    def __init__(self):
        super().__init__()
        self.retry_after = None

    def send(self, kind, spoof=None):
        method, host, path, body = KINDS[kind]
        headers = {}
        if spoof:
            target = os.environ.get("SPOOF_ADDRESS", "10.203.9.12")
            headers = {name: value.format(target=target) for name, value in SPOOFS[spoof].items()}
        if body is not None:
            headers["Content-Type"] = "application/json" if host == APPLICATION_HOST else "application/x-www-form-urlencoded"
        return exchange(host, path, method=method, headers=headers, body=body)

    def check(self, label, kind, expected, response):
        _, host, path, _ = KINDS[kind]
        if response is None:
            self.expect(f"{label} {kind} aborted", expected == "aborted", "transport failure")
            return
        status, headers, body = response
        if expected == "aborted":
            self.expect(f"{label} {kind} aborted", False, f"answered {status}")
            return
        echoed = headers.get("content-type") == "application/json" and b'"upstream"' in body
        if expected == "ok":
            self.expect(f"{label} {kind} reaches its upstream", status == 200 and echoed, f"-> {status}")
            return
        codes = {"limited": (429, "RATE_LIMITED", "Demasiadas solicitudes seguidas"),
                 "overloaded": (503, "SERVICE_OVERLOADED", "demasiadas solicitudes"),
                 "maintenance": (503, "SERVICE_MAINTENANCE", "en mantenimiento")}
        status_expected, code, text = codes[expected]
        name = f"{label} {kind} {expected}"
        self.expect(f"{name} status", status == status_expected and not echoed, f"-> {status}, upstream reached {echoed}")
        retry = headers.get("retry-after", "")
        # Within the longest limiter window plus jitter; the contract test scales some to 5 minutes.
        self.expect(f"{name} Retry-After", retry.isdigit() and (retry == "300" if expected == "maintenance" else 1 <= int(retry) <= 361),
                    repr(retry))
        self.retry_after = int(retry) if retry.isdigit() else None
        self.expect(f"{name} is not stored", headers.get("cache-control") == "no-store", str(headers.get("cache-control")))
        correlation = headers.get("x-correlation-id", "")
        self.expect(f"{name} correlation", 0 < len(correlation) <= 200, repr(correlation))
        self.expect(f"{name} hides Server", "server" not in headers, headers.get("server", ""))
        if host == APPLICATION_HOST and path.startswith("/api/"):
            try:
                problem = json.loads(body)
            except ValueError:
                problem = {}
            self.expect(f"{name} problem", headers.get("content-type") == "application/problem+json"
                        and problem.get("status") == status_expected and problem.get("code") == code
                        and problem.get("category") == "technical" and problem.get("correlationId") == correlation
                        and all(problem.get(k) for k in ("type", "title", "detail", "instance")), body[:200].decode("utf-8", "replace"))
        else:
            self.expect(f"{name} page", headers.get("content-type", "").startswith("text/html")
                        and text in body.decode("utf-8", "replace"), body[:200].decode("utf-8", "replace"))

    def run(self, steps, pace=0.0):
        """Steps are kind[~spoof]:count:expected, or pause:<seconds> / pause:retry-after."""
        for step in steps:
            if step.startswith("pause:"):
                seconds = self.retry_after if step == "pause:retry-after" else float(step.split(":", 1)[1])
                self.expect("a refusal announced when to retry", seconds is not None)
                time.sleep((seconds or 0) + 0.5)
                continue
            target, count, expected = step.split(":")
            kind, _, spoof = target.partition("~")
            assert kind in KINDS and expected in EXPECTED and (not spoof or spoof in SPOOFS), step
            for index in range(int(count)):
                try:
                    response = self.send(kind, spoof or None)
                except (OSError, http.client.HTTPException):
                    response = None
                self.check(f"#{index + 1}" + (f" spoofed {spoof}" if spoof else ""), kind, expected, response)
                if pace:
                    time.sleep(pace)
        return self


# Legitimate traffic from one shared household address: four people loading the site,
# browsing, three signing in, one registering and one recovering a password, at a browser
# pace of about ten requests a second. None of it may be refused.
HOUSEHOLD_PERSON = ["shell", *["asset"] * 20, "session", "releases", "read", "search", "search", "read",
                    "read", "write", "write"]
HOUSEHOLD_SIGN_IN = ["navigation", "identity-page", *["identity-resource"] * 6, "login", "callback", "session"]
HOUSEHOLD_REGISTRATION = ["navigation", "identity-page", *["identity-resource"] * 6, "registration", "verification",
                          "action-link", "required-action", "callback"]
HOUSEHOLD_RECOVERY = ["navigation", "identity-page", "recovery", "action-link", "required-action", "callback"]


def household():
    check = Limits()
    journey = HOUSEHOLD_PERSON * 4 + HOUSEHOLD_SIGN_IN * 3 + HOUSEHOLD_REGISTRATION + HOUSEHOLD_RECOVERY
    check.run([f"{kind}:1:ok" for kind in journey], pace=0.1)
    return check


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "serve" and len(sys.argv) == 3:
        serve(sys.argv[2])
        return 0
    if mode == "limit" and len(sys.argv) >= 3:
        check = household() if sys.argv[2:] == ["household"] else Limits().run(sys.argv[2:])
    elif mode in ("probe", "backchannel"):
        check = probe() if mode == "probe" else backchannel()
    else:
        print("usage: probe-public-production-edge.py serve <tag> | probe | backchannel | limit <steps...>",
              file=sys.stderr)
        return 2
    for violation in check.violations:
        print(f"VIOLATION {violation}")
    print(f"{mode}: {check.checks} checks, {len(check.violations)} violation(s)")
    return 1 if check.violations else 0


if __name__ == "__main__":
    sys.exit(main())
