"""Contract tests for the public-production runtime candidate (#275 increments 1 and 6).

Default: render deploy/public-production with synthetic inputs and check topology, exposure,
images, secrets and forbidden options; build a disposable edge image from caddy/Dockerfile,
adapt the Caddyfile and check its global options and limiter settings; exercise the real
Caddy routes, per-source and aggregate request limits, spoofing, IPv4/IPv6 sources and the
maintenance gate on disposable internal networks with upstream stand-ins; then prove that
each deliberate violation is detected. Nothing is published to the host and no DNS, ACME,
credential, private-dev configuration or data is used. The test image is never published.

--env-file PATH  render a protected runtime.env instead and run only the static Compose
                 checks (no containers). Secret files are never read.
--static-only    skip the disposable edge exercise.
"""

import argparse
import copy
import json
import os
import pathlib
import re
import secrets
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
RUNTIME = ROOT / "deploy/public-production"
COMPOSE_FILE = RUNTIME / "compose.yaml"
CADDYFILE = RUNTIME / "caddy/Caddyfile"
ENV_EXAMPLE = RUNTIME / "runtime.env.example"
PROBE = ROOT / "scripts/probe-public-production-edge.py"
PRIVATE_DEV_SECRETS = "/etc/videogame-platform/dev/secrets"
EDGE_DOCKERFILE = RUNTIME / "caddy/Dockerfile"
EDGE_TEST_IMAGE = f"videogame-platform-edge-test:{secrets.token_hex(6)}"
# The localizer's pinned base image doubles as the stub and probe runtime.
PYTHON_IMAGE = "python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258"

APPLICATION_ORIGIN = "https://gameometro.com"
IDENTITY_ORIGIN = "https://auth.gameometro.com"
ISSUER = f"{IDENTITY_ORIGIN}/realms/gameometro"
EDGE_ADDRESSES = {"application-edge": "10.203.1.2", "identity-edge": "10.203.2.2"}
# The BFF's fixed address, whose issuer calls the edge budgets separately, and the gateways
# behind which Docker's userland proxy hides a client; the Caddyfile names both.
BFF_ADDRESS = "10.203.1.3"
GATEWAYS = {"application-edge": "10.203.1.1", "identity-edge": "10.203.2.1"}
DIGEST = re.compile(r"^[a-z0-9./_:-]+@sha256:[0-9a-f]{64}$")
# Each image comes from the GHCR repository that trusted main publishes it to.
TRUSTED_REPOSITORIES = {
    "application": "ghcr.io/rubhern/videogame-platform",
    "migration": "ghcr.io/rubhern/videogame-platform",
    "keycloak": "ghcr.io/rubhern/videogame-platform-keycloak",
    "catalogue-localizer": "ghcr.io/rubhern/videogame-platform-catalogue-localizer",
    "caddy": "ghcr.io/rubhern/videogame-platform-caddy",
}
PLACEHOLDER = re.compile(r"=(.*@sha256:0{64}|.*REPLACE_WITH)")
INLINE_SECRET_NAME = re.compile(r"(PASSWORD|SECRET|TOKEN|CLIENT_ID|SMTP_USER)$")
MIB = 1024 * 1024

SERVICES = {"caddy", "postgres", "keycloak", "catalogue-localizer", "application", "migration"}
NETWORKS = {
    "caddy": {"application-edge", "identity-edge"},
    "postgres": {"application-data", "identity-data"},
    "keycloak": {"identity-edge", "identity-data"},
    "catalogue-localizer": {"application-data"},
    "application": {"application-edge", "application-data"},
    "migration": {"application-data"},
}
INTERNAL_NETWORKS = {"application-data", "identity-data"}
SECRETS = {
    "caddy": set(),
    "postgres": {"postgres_admin_password", "application_db_password", "application_migration_db_password",
                 "keycloak_db_password"},
    "keycloak": {"keycloak_db_password", "keycloak_admin_password", "keycloak_bff_client_secret",
                 "keycloak_smtp_username", "keycloak_smtp_password"},
    "catalogue-localizer": set(),
    "application": {"application_db_password", "keycloak_bff_client_secret", "igdb_client_id", "igdb_client_secret"},
    "migration": {"application_migration_db_password"},
}
SECRET_FILES = {
    "postgres_admin_password": "postgres-admin-password",
    "application_db_password": "application-db-password",
    "application_migration_db_password": "application-migration-db-password",
    "keycloak_db_password": "keycloak-db-password",
    "keycloak_admin_password": "keycloak-admin-password",
    "keycloak_bff_client_secret": "keycloak-bff-client-secret",
    "keycloak_smtp_username": "keycloak-smtp-username",
    "keycloak_smtp_password": "keycloak-smtp-password",
    "igdb_client_id": "igdb-client-id",
    "igdb_client_secret": "igdb-client-secret",
}
# Ceilings from the platform design's qualification envelope, in MiB.
MEMORY = {"caddy": 128, "postgres": 768, "keycloak": 768, "catalogue-localizer": 768, "application": 1536,
          "migration": 512}
STEADY_MEMORY_BUDGET_MIB = 3968  # 3.875 GiB: the envelope's steady services without telemetry.
# Read-only wrappers outside this directory that production may mount, and nothing else.
SHARED_MOUNTS = {
    ROOT / "docker/postgres/init",
    ROOT / "deploy/private-dev/bin/run-application",
    ROOT / "deploy/private-dev/bin/run-migrations",
}
ENTRYPOINT_WRAPPERS = (
    RUNTIME / "bin/run-postgres",
    RUNTIME / "bin/run-keycloak",
    RUNTIME / "bin/run-application",
    RUNTIME / "bin/run-migrations",
    ROOT / "deploy/private-dev/bin/run-application",
    ROOT / "deploy/private-dev/bin/run-migrations",
)
APPLICATION_ENVIRONMENT = {
    "SPRING_PROFILES_ACTIVE": "oidc,structured",
    "APPLICATION_PUBLIC_ORIGIN": APPLICATION_ORIGIN,
    "APPLICATION_OIDC_REDIRECT_URI": f"{APPLICATION_ORIGIN}/login/oauth2/code/keycloak",
    "APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI": f"{APPLICATION_ORIGIN}/",
    "PLATFORM_HTTPSECURITY_HSTS_ENABLED": "true",
    "SERVER_FORWARD_HEADERS_STRATEGY": "NATIVE",
    "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES": re.escape(EDGE_ADDRESSES["application-edge"]),
    "APPLICATION_FLYWAY_ENABLED": "false",
    "APPLICATION_API_ADMISSION_ENABLED": "false",
    "MANAGEMENT_SERVER_ADDRESS": "127.0.0.1",
    "OIDC_ISSUER_URI": ISSUER,
    "OIDC_AUTHORIZATION_URI": f"{ISSUER}/protocol/openid-connect/auth",
    "OIDC_TOKEN_URI": f"{ISSUER}/protocol/openid-connect/token",
    "OIDC_JWK_SET_URI": f"{ISSUER}/protocol/openid-connect/certs",
    "OIDC_USER_INFO_URI": f"{ISSUER}/protocol/openid-connect/userinfo",
    "OIDC_END_SESSION_URI": f"{ISSUER}/protocol/openid-connect/logout",
    "APPLICATION_SESSION_COOKIE_NAME": "__Host-vgp_session",
    "APPLICATION_SESSION_COOKIE_SECURE": "true",
    "TELEMETRY_DEPLOYMENT_ENVIRONMENT": "production",
    "CATALOGUE_SYNC_SCHEDULING_ENABLED": "false",
}
KEYCLOAK_ENVIRONMENT = {
    "KC_HOSTNAME": IDENTITY_ORIGIN,
    "KC_HTTP_ENABLED": "true",
    "KC_PROXY_HEADERS": "xforwarded",
    "KC_PROXY_TRUSTED_ADDRESSES": EDGE_ADDRESSES["identity-edge"],
    "KC_LOG_LEVEL": "info",
    "KC_HTTP_ACCESS_LOG_ENABLED": "false",
    "APPLICATION_PUBLIC_HOST": "gameometro.com",
    "APPLICATION_PUBLIC_ORIGIN": APPLICATION_ORIGIN,
}


def run(command, **options):
    return subprocess.run(command, check=True, text=True, capture_output=True, **options)


def render(env_file, project=None):
    command = ["docker", "compose", "--file", str(COMPOSE_FILE), "--env-file", str(env_file),
               "--profile", "application", "--profile", "deployment"]
    if project:
        command += ["--project-name", project]
    return json.loads(run(command + ["config", "--format", "json"]).stdout)


def megabytes(value):
    return int(value) // MIB if value is not None else None


def abuse_settings():
    lines = (RUNTIME / "keycloak/abuse.env").read_text().splitlines()
    return dict(line.split("=", 1) for line in lines if line and not line.startswith("#"))


# --- Compose contract ---------------------------------------------------------------


def compose_violations(config, secrets_dir):
    found = []
    services = config.get("services", {})
    if config.get("name") != "gameometro-production":
        found.append("project name must be gameometro-production, independent of private-dev")
    if set(services) != SERVICES:
        found.append(f"services must be exactly {sorted(SERVICES)}, found {sorted(services)}")

    for name, network in config.get("networks", {}).items():
        if network.get("external") or network.get("driver") not in (None, "bridge"):
            found.append(f"network {name} must be a project-owned bridge")
        if (name in INTERNAL_NETWORKS) != bool(network.get("internal")):
            found.append(f"network {name} internal flag differs from the isolation contract")
    for edge, address in EDGE_ADDRESSES.items():
        ipam = config.get("networks", {}).get(edge, {}).get("ipam", {}).get("config", [{}])
        prefix = address.rsplit(".", 1)[0]
        if len(ipam) != 1 or not str(ipam[0].get("subnet", "")).startswith(prefix + ".") or not ipam[0].get("ip_range"):
            found.append(f"network {edge} needs one fixed subnet with a dynamic ip_range excluding the edge address")
        elif ipam[0].get("gateway") != GATEWAYS[edge]:
            found.append(f"network {edge} gateway must be {GATEWAYS[edge]}, the address the edge refuses as unattributed")

    for name, service in services.items():
        found += service_violations(name, service, config, secrets_dir)

    steady = sum(megabytes(services.get(name, {}).get("mem_limit")) or 0 for name in MEMORY if name != "migration")
    if steady > STEADY_MEMORY_BUDGET_MIB:
        found.append(f"steady memory ceilings total {steady} MiB, above the {STEADY_MEMORY_BUDGET_MIB} MiB envelope")

    for name, definition in config.get("secrets", {}).items():
        expected = SECRET_FILES.get(name)
        path = pathlib.Path(definition.get("file", ""))
        if expected is None or definition.get("environment") or definition.get("external"):
            found.append(f"secret {name} is not a known file-backed production secret")
        elif path != pathlib.Path(secrets_dir) / expected:
            found.append(f"secret {name} must be the file {expected} in the production secrets directory")
    application, migration = services.get("application", {}), services.get("migration", {})
    # Migrations run from the same digest as the application they precede, and only as the
    # explicit deployment step; the application never migrates itself at startup.
    if application.get("image") != migration.get("image"):
        found.append("migration must run the same image digest as the application")
    if application.get("profiles") != ["application"] or migration.get("profiles") != ["deployment"]:
        found.append("application and migration must stay behind their explicit Compose profiles")
    if set(config.get("secrets", {})) != set(SECRET_FILES):
        found.append("the secret inventory differs from the documented production files")
    return found


def service_violations(name, service, config, secrets_dir):
    found = []
    image = service.get("image", "")
    if not DIGEST.match(image):
        found.append(f"{name} image must be referenced by sha256 digest, found {image!r}")
    repository = TRUSTED_REPOSITORIES.get(name)
    if repository and not re.fullmatch(re.escape(repository) + r"@sha256:[0-9a-f]{64}", image):
        found.append(f"{name} must run the CI-published GHCR digest of {repository}")
    if "build" in service:
        found.append(f"{name} must not build on the production host")
    if service.get("ports") and name != "caddy":
        found.append(f"{name} must not publish a port; only the edge does")
    if service.get("expose"):
        found.append(f"{name} must not declare extra exposed ports")
    for option in ("network_mode", "pid", "ipc", "userns_mode", "uts", "devices", "extra_hosts", "dns"):
        if service.get(option):
            found.append(f"{name} must not set {option}")
    if service.get("privileged"):
        found.append(f"{name} must not be privileged")
    if "no-new-privileges:true" not in service.get("security_opt", []):
        found.append(f"{name} must set no-new-privileges")
    if name != "postgres":
        if not service.get("read_only"):
            found.append(f"{name} must use a read-only root filesystem")
        if service.get("cap_drop") != ["ALL"]:
            found.append(f"{name} must drop all capabilities")
    allowed_capabilities = ["NET_BIND_SERVICE"] if name == "caddy" else []
    if (service.get("cap_add") or []) != allowed_capabilities:
        found.append(f"{name} may add only {allowed_capabilities or 'no'} capabilities")
    if megabytes(service.get("mem_limit")) is None or megabytes(service.get("mem_limit")) > MEMORY.get(name, 0):
        found.append(f"{name} needs a memory ceiling within {MEMORY.get(name)} MiB")
    if not service.get("cpus") or not service.get("pids_limit"):
        found.append(f"{name} needs CPU and PID limits")
    logging = service.get("logging", {})
    if logging.get("driver") != "local" or not logging.get("options", {}).get("max-size") \
            or not logging.get("options", {}).get("max-file"):
        found.append(f"{name} needs bounded local Docker logs")
    if name not in ("migration",) and service.get("restart") != "unless-stopped":
        found.append(f"{name} must restart unless stopped")

    networks = service.get("networks") or {}
    if set(networks) != NETWORKS.get(name, set()):
        found.append(f"{name} networks must be exactly {sorted(NETWORKS.get(name, set()))}, found {sorted(networks)}")
    for network, attachment in networks.items():
        attachment = attachment or {}
        expected_address = EDGE_ADDRESSES.get(network) if name == "caddy" else \
            BFF_ADDRESS if (name, network) == ("application", "application-edge") else None
        if attachment.get("ipv4_address") != expected_address or attachment.get("ipv6_address"):
            found.append(f"{name} on {network} must use {expected_address or 'a dynamic address'}")
        aliases = set(attachment.get("aliases") or [])
        expected_aliases = {"auth.gameometro.com"} if (name, network) == ("caddy", "application-edge") else set()
        if aliases != expected_aliases:
            found.append(f"{name} on {network} must have aliases {sorted(expected_aliases)}")

    granted = {entry.get("source") for entry in service.get("secrets") or []}
    if granted != SECRETS.get(name, set()):
        found.append(f"{name} secrets must be exactly {sorted(SECRETS.get(name, set()))}, found {sorted(granted)}")
    environment = service.get("environment") or {}
    for key, value in environment.items():
        if INLINE_SECRET_NAME.search(key):
            found.append(f"{name} passes {key} inline; secrets travel only as *_FILE paths")
        if key.endswith("_FILE") and not str(value).startswith("/run/secrets/"):
            found.append(f"{name} {key} must point below /run/secrets")
    for option in ("command", "entrypoint"):
        for argument in service.get(option) or []:
            if "/run/secrets" in argument or INLINE_SECRET_NAME.search(argument.upper()):
                found.append(f"{name} {option} must not carry a secret or its path")
    if service.get("env_file") and name != "keycloak":
        found.append(f"{name} must not load an env_file")
    if granted and not service.get("group_add"):
        found.append(f"{name} must join the production secrets group to read its files")
    if not granted and service.get("group_add"):
        found.append(f"{name} must not join the secrets group without a secret")

    for volume in service.get("volumes") or []:
        source = volume.get("source", "")
        if volume.get("type") == "bind":
            path = pathlib.Path(source)
            if "docker.sock" in source or source in ("/", "/var/run", "/run", "/etc", "/proc", "/sys"):
                found.append(f"{name} must not mount host runtime path {source}")
            elif name == "catalogue-localizer" and volume.get("target") == "/model":
                # Some Compose releases omit a false create_host_path from rendered output, so the
                # reviewed source must state it and the rendering must never enable it.
                if not volume.get("read_only") or volume.get("bind", {}).get("create_host_path") is True \
                        or "create_host_path: false" not in COMPOSE_FILE.read_text():
                    found.append("catalogue-localizer model must be an existing read-only bind")
            elif not volume.get("read_only"):
                found.append(f"{name} bind mount {source} must be read-only")
            elif not (path.is_relative_to(RUNTIME) or path in SHARED_MOUNTS):
                found.append(f"{name} mounts {source}, outside the production directory and shared wrappers")
            elif not path.exists():
                found.append(f"{name} mounts {source}, which does not exist in this checkout")
            if pathlib.Path(secrets_dir) in (path, *path.parents):
                found.append(f"{name} must receive secrets as Compose secrets, not a directory mount")
        elif volume.get("type") == "volume":
            expected = {"caddy": {"caddy-data", "caddy-config", "recovery-state"}, "postgres": {"postgres-data"}, "application": {"recovery-state"}, "migration": {"recovery-state"}}.get(name, set())
            if source not in expected:
                found.append(f"{name} must not use named volume {source}")
            elif source == "recovery-state" and not volume.get("read_only"):
                found.append(f"{name} must mount the recovery gate read-only")
        else:
            found.append(f"{name} uses unsupported volume type {volume.get('type')}")

    if name == "caddy":
        published = sorted((port.get("host_ip", ""), str(port.get("published")), port.get("target"),
                            port.get("protocol")) for port in service.get("ports") or [])
        if published != [("", "443", 443, "tcp"), ("", "80", 80, "tcp")]:
            found.append(f"edge must publish exactly TCP 80 and 443, found {published}")
        if service.get("command") or service.get("entrypoint"):
            found.append("edge must run the image's Caddyfile command")
        targets = {volume.get("target"): volume for volume in service.get("volumes") or []}
        caddyfile = targets.get("/etc/caddy/Caddyfile", {})
        if pathlib.Path(caddyfile.get("source", "")) != CADDYFILE or not caddyfile.get("read_only"):
            found.append("edge must mount only the reviewed Caddyfile, read-only")
        if targets.get("/data", {}).get("type") != "volume":
            found.append("edge TLS state must persist in a named volume")
        gate = targets.get("/run/recovery", {})
        if gate.get("type") != "volume" or gate.get("source") != "recovery-state" or not gate.get("read_only"):
            found.append("edge must read the recovery and identity-maintenance gate, read-only")
    if name in ("application", "migration"):
        wrapper = "run-application" if name == "application" else "run-migrations"
        targets = {v.get("target"): v for v in service.get("volumes", [])}
        gate = targets.get("/run/recovery", {})
        guard = targets.get("/opt/gameometro/bin/" + wrapper, {})
        if service.get("entrypoint") != ["/opt/gameometro/bin/" + wrapper] or service.get("command") \
                or pathlib.Path(guard.get("source", "")) != RUNTIME / "bin" / wrapper \
                or not guard.get("read_only") or gate.get("source") != "recovery-state" \
                or gate.get("type") != "volume" or not gate.get("read_only"):
            found.append(f"{name} must retain the production recovery startup guard and read-only authorization volume")
    if name == "application":
        found += environment_violations(name, environment, APPLICATION_ENVIRONMENT)
    if name == "keycloak":
        found += environment_violations(name, environment, {**KEYCLOAK_ENVIRONMENT, **abuse_settings()})
        imports = [volume for volume in service.get("volumes") or [] if volume.get("target") == "/opt/keycloak/data/import"]
        if len(imports) != 1 or pathlib.Path(imports[0].get("source", "")) != RUNTIME / "keycloak/import" \
                or not imports[0].get("read_only"):
            found.append("keycloak must import only the production realm directory, read-only")
        if any(str(volume.get("target", "")).startswith("/opt/keycloak/data/import/") for volume in service.get("volumes") or []):
            found.append("keycloak must not add files to the production import directory")
    return found


def environment_violations(name, environment, expected):
    return [f"{name} {key} must be {value!r}, found {environment.get(key)!r}"
            for key, value in expected.items() if str(environment.get(key)) != value]


def env_example_violations(config):
    referenced = set(re.findall(r"\$\{([A-Z0-9_]+)", COMPOSE_FILE.read_text()))
    documented = {line.split("=", 1)[0] for line in ENV_EXAMPLE.read_text().splitlines()
                  if line and not line.startswith("#")}
    found = []
    if referenced != documented:
        found.append(f"runtime.env.example and compose.yaml disagree: "
                     f"missing {sorted(referenced - documented)}, unused {sorted(documented - referenced)}")
    example = dict(line.split("=", 1) for line in ENV_EXAMPLE.read_text().splitlines()
                   if line and not line.startswith("#"))
    directory = example.get("PRODUCTION_SECRETS_DIR", "")
    if not directory.startswith("/") or directory == PRIVATE_DEV_SECRETS or pathlib.Path(directory).is_relative_to(ROOT):
        found.append("the example secrets directory must be absolute and independent of private-dev and Git")
    return found


def secrets_dir_violations(secrets_dir):
    path = pathlib.Path(secrets_dir)
    if not path.is_absolute():
        return ["PRODUCTION_SECRETS_DIR must be absolute"]
    if str(path) == PRIVATE_DEV_SECRETS or path.is_relative_to(pathlib.Path(PRIVATE_DEV_SECRETS).parent):
        return ["PRODUCTION_SECRETS_DIR must not share the private-dev secret tree"]
    if path.is_relative_to(ROOT):
        return ["PRODUCTION_SECRETS_DIR must be outside the repository"]
    return []


def wrapper_violations():
    found = []
    for wrapper in ENTRYPOINT_WRAPPERS:
        text = wrapper.read_text()
        exec_lines = [line for line in text.splitlines() if line.strip().startswith("exec ")]
        if len(exec_lines) != 1 or "$" in exec_lines[0]:
            found.append(f"{wrapper.relative_to(ROOT)} must exec once without expanding a variable into arguments")
        if re.search(r"(echo|printf(?! -v))[^\n]*\$\{?(secret_value|migration_password)(?![A-Za-z_])", text):
            found.append(f"{wrapper.relative_to(ROOT)} must never print a secret value")
    return found


# --- Caddy contract -----------------------------------------------------------------


def adapt(caddyfile_text, directory):
    path = pathlib.Path(directory) / "Caddyfile"
    path.write_text(caddyfile_text)
    output = run(["docker", "run", "--rm", "--network", "none", "--read-only",
                  "--volume", f"{path}:/etc/caddy/Caddyfile:ro", EDGE_TEST_IMAGE,
                  "caddy", "adapt", "--config", "/etc/caddy/Caddyfile"]).stdout
    return json.loads(output)


def walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from walk(value)


def caddy_violations(config):
    found = []
    if config.get("admin") != {"disabled": True}:
        found.append("Caddy admin API must be disabled")
    if set(config.get("apps", {})) - {"http"}:
        found.append(f"Caddy must not configure apps {sorted(set(config['apps']) - {'http'})}")
    servers = config.get("apps", {}).get("http", {}).get("servers", {})
    if sorted(server.get("listen", [None])[0] for server in servers.values()) != [":443", ":80"]:
        found.append("Caddy must listen only on :80 and :443")
    for name, server in servers.items():
        if server.get("protocols") != ["h1", "h2"]:
            found.append(f"Caddy server {name} must serve only HTTP/1.1 and HTTP/2")
        if server.get("strict_sni_host") is not True:
            found.append(f"Caddy server {name} must enforce strict SNI/Host matching")
        if server.get("trusted_proxies") or server.get("client_ip_headers"):
            found.append(f"Caddy server {name} must not trust a client-supplied proxy chain")
        if not server.get("read_header_timeout") or not server.get("idle_timeout") or not server.get("max_header_bytes"):
            found.append(f"Caddy server {name} must bound header time, idle time and header size")
        if server.get("automatic_https", {}).get("disable") or server.get("automatic_https", {}).get("disable_redirects"):
            found.append(f"Caddy server {name} must keep automatic HTTPS and its redirects")
    nodes = list(walk(config))
    dials = {upstream["dial"] for node in nodes if node.get("handler") == "reverse_proxy" for upstream in node.get("upstreams", [])}
    if dials != {"application:8080", "keycloak:8080"}:
        found.append(f"Caddy must proxy only to application:8080 and keycloak:8080, found {sorted(dials)}")
    for node in nodes:
        if node.get("handler") == "reverse_proxy":
            removed = set(node.get("headers", {}).get("request", {}).get("delete", []))
            missing = {"Forwarded", "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Forwarded-Server", "X-Real-Ip"} - removed
            if missing:
                found.append(f"Caddy upstream {node['upstreams'][0]['dial']} must drop {sorted(missing)}")
            if node.get("transport", {}).get("tls", None) is not None and node["transport"]["tls"].get("insecure_skip_verify"):
                found.append("Caddy must not skip upstream TLS verification")
        if node.get("handler") in ("file_server", "metrics", "pprof", "vars", "authentication"):
            found.append(f"Caddy must not enable the {node['handler']} handler")
        if node.get("module") == "internal" or node.get("on_demand"):
            found.append("Caddy production TLS must not use the internal CA or on-demand issuance")
    found += limiter_violations(config, nodes)
    return found


PEER_KEY = "{http.request.remote.host}"


def limiter_violations(config, nodes):
    """Static limiter policy; the edge exercise proves the behaviour of every zone."""
    found = []
    if "http.handlers.rate_limit" not in config.get("logging", {}).get("logs", {}).get("default", {}).get("exclude", []):
        found.append("Caddy must not log limiter refusals, which carry the client address")
    zones = []
    for node in nodes:
        if node.get("handler") != "rate_limit":
            continue
        if node.get("disable_metrics") is not True:
            found.append("Caddy limiter metrics must stay disabled: they label each client address")
        if node.get("log_key") or "distributed" in node or "storage" in node:
            found.append("Caddy limiter must not log keys or share state through storage")
        kinds = set()
        for name, zone in node.get("rate_limits", {}).items():
            zones.append(name)
            key = zone.get("key")
            kinds.add("aggregate" if key == "static" else "source")
            if name.startswith("aggregate-") != (key == "static"):
                found.append(f"Caddy zone {name}: only aggregate-* zones may use the static key")
            if key not in ("static", PEER_KEY):
                found.append(f"Caddy zone {name} must key on the TCP peer, found {key!r}")
            if key == PEER_KEY and (zone.get("ipv6_prefix") != 64 or zone.get("ipv4_prefix")):
                found.append(f"Caddy zone {name} must group IPv6 sources by /64 and keep each IPv4 address")
        if len(kinds) != 1:
            found.append("Caddy per-source and aggregate zones must be separate handlers, per-source first")
    if len(zones) != len(set(zones)):
        found.append("Caddy limiter zone names must be unique: equal names share state")
    # In every route that proxies, the per-source handler precedes the aggregate one.
    for node in nodes:
        handlers = [h for route in node.get("routes", []) for h in route.get("handle", [])] if "routes" in node else []
        order = [("proxy" if h.get("handler") == "reverse_proxy" else
                  "aggregate" if all(z.get("key") == "static" for z in h.get("rate_limits", {}).values()) else "source")
                 for h in handlers if h.get("handler") in ("rate_limit", "reverse_proxy")]
        if "proxy" in order and order != ["source", "aggregate", "proxy"]:
            found.append(f"Caddy must limit per source, then in aggregate, before proxying; found {order}")
    unattributed = [node for node in nodes for match in node.get("match", []) if isinstance(match, dict)
                    and match.get("remote_ip", {}).get("ranges") == sorted(GATEWAYS.values())
                    and '"abort": true' in json.dumps(node.get("handle", []))]
    if len(unattributed) != 2:
        found.append("Caddy must abort requests from the edge network gateways on both hosts")
    gates = json.dumps(config)
    for marker in ("/READY", "/PENDING.json", "/IDENTITY-MAINTENANCE.json"):
        if f'"{marker}"' not in gates:
            found.append(f"Caddy must read the {marker} gate file")
    return found


def edge_test_config(config):
    """The adapted production config with a disposable internal CA instead of ACME."""
    patched = copy.deepcopy(config)
    patched.setdefault("apps", {})["tls"] = {"automation": {"policies": [
        {"subjects": ["gameometro.com", "auth.gameometro.com"], "issuers": [{"module": "internal"}]}]}}
    patched["apps"]["pki"] = {"certificate_authorities": {"local": {"install_trust": False}}}
    return patched


# --- Disposable edge exercise -------------------------------------------------------


# Client addresses on the private probe network. Two IPv6 clients share one /64.
PROBE_EDGE = {"ipv4": "10.203.9.2", "ipv6": "fd00:203:9::2"}
SOURCES = {
    "source-a": {"ipv4_address": "10.203.9.11"},
    "source-b": {"ipv4_address": "10.203.9.12"},
    "source-v6-a": {"ipv4_address": "10.203.9.21", "ipv6_address": "fd00:203:9:1::11"},
    "source-v6-b": {"ipv4_address": "10.203.9.22", "ipv6_address": "fd00:203:9:1::12"},
    "source-v6-c": {"ipv4_address": "10.203.9.23", "ipv6_address": "fd00:203:9:2::11"},
}
# Gate states the recovery actor and the identity-maintenance adapter write.
GATE_SCRIPT = """import json,pathlib,sys
p=pathlib.Path('/gate')
for name in ('READY','PENDING.json','IDENTITY-MAINTENANCE.json'):
    (p/name).unlink(missing_ok=True)
state=sys.argv[1]
if state in ('ready','pending','identity'):
    (p/'READY').touch()
if state=='pending':
    (p/'PENDING.json').write_text('{}')
if state=='identity':
    (p/'IDENTITY-MAINTENANCE.json').write_text(json.dumps({'runId':'00000000-0000-4000-8000-000000000000'}))
"""


def overlay(directory, caddy_config):
    probe_mount = f"{PROBE}:/probe/edge.py:ro"
    hardened = {"image": PYTHON_IMAGE, "read_only": True, "user": "65534:65534", "cap_drop": ["ALL"],
                "security_opt": ["no-new-privileges:true"], "mem_limit": "64m", "pids_limit": 64,
                "volumes": [probe_mount]}
    command = ["python", "-I", "/probe/edge.py"]
    sources = {name: {**hardened, "profiles": ["probe"], "command": [*command, "limit", "household"],
                      # Pin the address family: the probe network gives every client both.
                      "environment": {"EDGE_ADDRESS": PROBE_EDGE["ipv6" if "ipv6_address" in address else "ipv4"]},
                      "networks": {"probe": address}} for name, address in SOURCES.items()}
    document = {
        "services": {
            "caddy": {
                "image": EDGE_TEST_IMAGE,
                "ports": "!reset",
                "command": ["caddy", "run", "--config", "/etc/caddy/caddy.json"],
                "volumes": [f"{caddy_config}:/etc/caddy/caddy.json:ro", "caddy-data:/data", "caddy-config:/config",
                            "recovery-state:/run/recovery:ro"],
                "networks": {"probe": {"aliases": ["gameometro.com", "auth.gameometro.com"],
                                       "ipv4_address": PROBE_EDGE["ipv4"], "ipv6_address": PROBE_EDGE["ipv6"]}},
            },
            "application-stub": {**hardened, "command": [*command, "serve", "application"],
                                 "networks": {"application-edge": {"aliases": ["application"]}}},
            "keycloak-stub": {**hardened, "command": [*command, "serve", "keycloak"],
                              "networks": {"identity-edge": {"aliases": ["keycloak"]}}},
            "prober": {**hardened, "profiles": ["probe"], "command": [*command, "probe"], "networks": ["probe"]},
            # The BFF's fixed address, and another client on the same network.
            "backchannel-prober": {**hardened, "profiles": ["probe"], "command": [*command, "backchannel"],
                                   "networks": {"application-edge": {"ipv4_address": BFF_ADDRESS}}},
            "rogue-backchannel": {**hardened, "profiles": ["probe"], "command": [*command, "backchannel"],
                                  "networks": {"application-edge": {"ipv4_address": "10.203.1.4"}}},
            **sources,
            "gate": {**hardened, "user": "0:0", "profiles": ["probe"], "network_mode": "none",
                     "volumes": ["recovery-state:/gate"], "entrypoint": ["python", "-I", "-c", GATE_SCRIPT]},
        },
        "networks": {"probe": {"internal": True, "enable_ipv6": True,
                               "ipam": {"config": [{"subnet": "10.203.9.0/24"}, {"subnet": "fd00:203:9::/56"}]}}},
    }
    # JSON is YAML; "!reset" must stay a tag, so it is written unquoted.
    text = json.dumps(document, indent=2).replace('"ports": "!reset"', '"ports": !reset []')
    path = pathlib.Path(directory) / "compose.edge-test.yaml"
    path.write_text(text)
    return path


class Edge:
    def __init__(self, directory, env_file):
        self.directory = pathlib.Path(directory)
        self.caddy_config = self.directory / "caddy.json"
        self.caddy_config.write_text("{}")
        self.project = f"gameometro-production-edge-test-{secrets.token_hex(4)}"
        self.compose = ["docker", "compose", "--project-name", self.project, "--file", str(COMPOSE_FILE),
                        "--file", str(overlay(self.directory, self.caddy_config)), "--env-file", str(env_file)]
        self.started = False

    def __enter__(self):
        return self

    def __exit__(self, *_):
        if self.started:
            subprocess.run(self.compose + ["--profile", "probe", "down", "--volumes", "--remove-orphans"],
                           capture_output=True, text=True)

    def load(self, config, gate=None):
        """(Re)start Caddy on a config, which also clears every limiter's state."""
        # Rewrite in place so the running container's bind mount sees the new content.
        with open(self.caddy_config, "w") as handle:
            json.dump(config, handle)
        if gate:
            self.gate(gate)
        if not self.started:
            self.started = True
            run(self.compose + ["up", "--detach", "--wait", "--wait-timeout", "60",
                                "caddy", "application-stub", "keycloak-stub"])
        else:
            run(self.compose + ["restart", "caddy"])
        time.sleep(1)

    def gate(self, state):
        run(self.compose + ["--profile", "probe", "run", "--rm", "--no-deps", "--quiet-pull", "gate", state])

    def probe(self, service, *arguments):
        command = ["python", "-I", "/probe/edge.py", *arguments] if arguments else []
        for attempt in range(20):
            result = subprocess.run(self.compose + ["--profile", "probe", "run", "--rm", "--no-deps", "--quiet-pull",
                                                    service, *command], capture_output=True, text=True)
            # Wait for the internal CA certificates rather than reporting a startup race.
            if "transport failure" not in result.stdout or attempt == 19 or "aborted" in " ".join(arguments):
                output = result.stdout if result.stdout.strip() else result.stderr
                return result.returncode, output
            time.sleep(1)
        raise AssertionError("unreachable")


# Each scenario is (label, gate state, [(client, steps...)]); Caddy restarts before each one,
# so it starts with empty limiters. Counts are the Caddyfile's per-source zones.
LIMIT = "limit"
BURSTS = [
    ("application per-source", [("source-a", "shell:120:ok", "shell:1:limited")]),
    ("BFF navigation per-source", [("source-a", "navigation:10:ok", "navigation:1:limited", "callback:1:limited")]),
    ("catalogue read per-source and quota recovery",
     [("source-a", "read:40:ok", "read:1:limited", "pause:retry-after", "read:1:ok")]),
    ("expensive search per-source", [("source-a", "search:20:ok", "releases:1:limited", "read:1:ok")]),
    ("product write per-source", [("source-a", "write:30:ok", "write:1:limited", "read:1:ok")]),
    ("identity per-source", [("source-a", "identity-page:30:ok", "identity-resource:1:limited")]),
    ("login per-source", [("source-a", "login:10:ok", "login:1:limited", "token:1:limited")]),
    ("registration per-source", [("source-a", "registration:3:ok", "registration-slashes:3:ok", "registration:1:limited",
                                 "registration-slashes:1:limited", "login:1:ok")]),
    ("recovery per-source", [("source-a", "recovery:6:ok", "recovery:1:limited", "registration:1:ok")]),
    ("verification mail per-source", [("source-a", "verification:6:ok", "verification:1:limited")]),
    ("emailed action per-source", [("source-a", "action-link:8:ok", "required-action:7:ok", "action-link:1:limited")]),
    # A client cannot escape its quota, or charge another source, with any forwarding header.
    ("forged forwarding headers", [
        ("source-a", "read~xff:40:ok", "read:1:limited", *[f"read~{spoof}:1:limited" for spoof in
                                                           ("xff", "xff-chain", "real-ip", "forwarded", "client-ip",
                                                            "true-client-ip", "cluster-client-ip")]),
        ("source-b", "read:40:ok", "read:1:limited"),
    ]),
    ("IPv4 and IPv6 /64 sources", [
        ("source-v6-a", "read:40:ok", "read:1:limited"),
        ("source-v6-b", "read:1:limited"),
        ("source-v6-c", "read:40:ok", "read:1:limited"),
        ("source-a", "read:40:ok"),
    ]),
    ("shared household", [("source-a", "household")]),
    # The BFF's issuer calls have their own budget; nothing else from its address does.
    ("BFF backchannel", [
        ("backchannel-prober", "token:50:ok", "userinfo:10:ok", "certs:10:ok", "login:10:ok", "login:1:limited"),
        ("rogue-backchannel", "token:10:ok", "token:1:limited"),
    ]),
]
# Aggregate budgets scaled down: the first source's refused requests must not consume them.
AGGREGATE_SCALE = [("zone aggregate-application-api {\n\t\t\t\tmatch {\n\t\t\t\t\tpath /api/*\n\t\t\t\t}\n\t\t\t\tkey static\n\t\t\t\twindow 10s\n\t\t\t\tevents 600",
                    "zone aggregate-application-api {\n\t\t\t\tmatch {\n\t\t\t\t\tpath /api/*\n\t\t\t\t}\n\t\t\t\tkey static\n\t\t\t\twindow 2m\n\t\t\t\tevents 50"),
                   ("\t\t\t\t\tkey static\n\t\t\t\t\twindow 1m\n\t\t\t\t\tevents 60\n",
                    "\t\t\t\t\tkey static\n\t\t\t\t\twindow 5m\n\t\t\t\t\tevents 8\n")]
AGGREGATES = [
    ("aggregate API capacity", [("source-a", "read:40:ok", "read:5:limited"),
                                ("source-b", "read:10:ok", "read:1:overloaded", "shell:1:ok")]),
    ("aggregate identity mail", [("source-a", "registration:6:ok", "registration:1:limited"),
                                 ("source-b", "recovery:2:ok", "recovery:1:overloaded", "verification:1:overloaded",
                                  "login:1:ok")]),
]
# Treating one probe client as the gateway shows how the edge refuses an unattributed source.
UNATTRIBUTED = ("@unattributed remote_ip 10.203.1.1 10.203.2.1", "@unattributed remote_ip 10.203.9.11",
                [("source-a", "shell:1:aborted", "identity-page:1:aborted"), ("source-b", "shell:1:ok", "identity-page:1:ok")])
APPLICATION_CHECKS = "shell:1:{0} read:1:{0} write:1:{0} navigation:1:{0} callback:1:{0}"
IDENTITY_CHECKS = ("identity-page:1:{0} login:1:{0} registration:1:{0} recovery:1:{0} verification:1:{0} "
                   "required-action:1:{0} action-link:1:{0} identity-resource:1:{0}")
MAINTENANCE = [
    ("empty gate fails closed", "empty", [("source-a", *(APPLICATION_CHECKS + " " + IDENTITY_CHECKS).format("maintenance").split()),
                                          ("backchannel-prober", LIMIT, "token:1:maintenance")]),
    ("interrupted recovery", "pending", [("source-a", *(APPLICATION_CHECKS + " " + IDENTITY_CHECKS).format("maintenance").split())]),
    # Identity maintenance closes the whole identity host, BFF calls included; refused
    # requests consume no quota, so the same client is admitted in full after release.
    ("identity maintenance", "identity", [("source-a", *APPLICATION_CHECKS.format("ok").split(),
                                           *IDENTITY_CHECKS.format("maintenance").split(), "identity-page:40:maintenance"),
                                          ("backchannel-prober", LIMIT, "token:1:maintenance", "certs:1:maintenance")]),
    ("identity maintenance survives an edge restart", "identity", [("source-a", "login:1:maintenance", "read:1:ok")]),
    ("safe reopening", "ready", [("source-a", "identity-page:30:ok", "identity-page:1:limited", "read:1:ok"),
                                 ("backchannel-prober", LIMIT, "token:1:ok")]),
]
# Each mutation must make one scenario fail with a named violation.
LIMIT_MUTATIONS = [
    ("limit keyed on a forwarding header", lambda text: text.replace("key {remote_host}", "key {header.X-Forwarded-For}"),
     "forged forwarding headers", "source-b"),
    ("IPv6 sources not grouped", lambda text: text.replace("\tipv6_prefix 64\n", ""), "IPv4 and IPv6 /64 sources", "source-v6-b"),
    ("BFF exemption by subnet", lambda text: text.replace("remote_ip 10.203.1.3", "remote_ip 10.203.1.0/28"),
     "BFF backchannel", "rogue-backchannel"),
    ("refusal body lost", lambda text: text.replace("\timport limit_errors\n", ""), "catalogue read per-source and quota recovery",
     "source-a"),
    # Merging the two handlers is refused statically ("separate handlers"): the limiter orders
    # zones by rate, and the scaled-down test aggregate would sort after the per-source zone.
]
GATE_MUTATIONS = [
    ("recovery gate ignored", lambda text: text.replace("\timport edge_admission\n", "", 1), "empty gate fails closed"),
    ("identity fence ignored", lambda text: text.replace("try_files /IDENTITY-MAINTENANCE.json", "try_files /ABSENT.json"),
     "identity maintenance"),
]


def run_scenario(edge, config, label, clients):
    failures = []
    for client, *steps in clients:
        arguments = steps if steps[:1] == [LIMIT] else [LIMIT, *steps]
        if steps and steps[0] in ("probe", "backchannel"):
            arguments = steps
        status, output = edge.probe(client, *arguments)
        if status != 0:
            failures.append(f"edge scenario '{label}' failed for {client}:\n{output}")
    return failures


def exercise_limits(edge, caddyfile, directory):
    """Every limit, spoofing, source-family, aggregate and maintenance scenario, then its mutations."""
    failures = []
    production = edge_test_config(adapt(caddyfile, directory))
    for label, clients in BURSTS:
        edge.load(production, gate="ready")
        failures += run_scenario(edge, production, label, clients)
    scaled_text = caddyfile
    for old, new in AGGREGATE_SCALE:
        assert scaled_text.count(old) == 1, f"aggregate scaling no longer applies: {old[:40]}"
        scaled_text = scaled_text.replace(old, new)
    scaled = edge_test_config(adapt(scaled_text, directory))
    for label, clients in AGGREGATES:
        edge.load(scaled, gate="ready")
        failures += run_scenario(edge, scaled, label, clients)
    for label, gate, clients in MAINTENANCE:
        if label.endswith("survives an edge restart"):
            edge.load(production)  # The gate is unchanged; only the edge restarts.
        else:
            edge.load(production, gate=gate)
        failures += run_scenario(edge, production, label, clients)
    gateway, client, clients = UNATTRIBUTED
    assert caddyfile.count(gateway) == 1, "the unattributed-source matcher no longer applies"
    edge.load(edge_test_config(adapt(caddyfile.replace(gateway, client), directory)), gate="ready")
    failures += run_scenario(edge, None, "unattributed source", clients)
    print(f"Edge limits: {len(BURSTS) + len(AGGREGATES) + 1} limit and {len(MAINTENANCE)} maintenance scenarios checked.")
    scenarios = {label: (None, clients) for label, clients in BURSTS + AGGREGATES}
    scenarios.update({label: (gate, clients) for label, gate, clients in MAINTENANCE})
    for label, mutate, scenario, client in LIMIT_MUTATIONS:
        mutated = mutate(caddyfile)
        assert mutated != caddyfile, f"mutation '{label}' no longer applies to the Caddyfile"
        edge.load(edge_test_config(adapt(mutated, directory)), gate="ready")
        clients = [entry for entry in scenarios[scenario][1]]
        outcome = run_scenario(edge, None, scenario, clients)
        if not any(f"for {client}:" in failure for failure in outcome):
            failures.append(f"edge did not detect deliberate limit violation: {label} (expected {client} to fail)")
    for label, mutate, scenario in GATE_MUTATIONS:
        mutated = mutate(caddyfile)
        assert mutated != caddyfile, f"mutation '{label}' no longer applies to the Caddyfile"
        gate, clients = scenarios[scenario]
        edge.load(edge_test_config(adapt(mutated, directory)), gate=gate)
        if not run_scenario(edge, None, scenario, clients):
            failures.append(f"edge did not detect deliberate maintenance violation: {label}")
    print(f"Edge limits: {len(LIMIT_MUTATIONS) + len(GATE_MUTATIONS)} deliberate limit and gate violations checked.")
    return failures


# --- Deliberate violations ----------------------------------------------------------


def compose_mutations(secrets_dir):
    def set_env(service, key, value):
        return lambda c: c["services"][service].setdefault("environment", {}).__setitem__(key, value)

    def port(service, published, target, protocol="tcp", host_ip=None):
        entry = {"mode": "ingress", "target": target, "published": published, "protocol": protocol}
        if host_ip:
            entry["host_ip"] = host_ip
        return lambda c: c["services"][service].setdefault("ports", []).append(entry)

    def attach(service, network):
        return lambda c: c["services"][service]["networks"].__setitem__(network, None)

    def grant(service, secret):
        return lambda c: c["services"][service].setdefault("secrets", []).append({"source": secret, "target": secret})

    def bind(service, source, target, read_only=True):
        return lambda c: c["services"][service].setdefault("volumes", []).append(
            {"type": "bind", "source": source, "target": target, "read_only": read_only})

    return [
        ("PostgreSQL publishes its port", port("postgres", "5432", 5432), "postgres must not publish"),
        ("Keycloak publishes its port", port("keycloak", "8080", 8080), "keycloak must not publish"),
        ("application publishes management on loopback", port("application", "8081", 8081, host_ip="127.0.0.1"),
         "application must not publish"),
        ("edge publishes HTTP/3 over UDP", port("caddy", "443", 443, "udp"), "exactly TCP 80 and 443"),
        ("edge publishes an extra port", port("caddy", "2019", 2019), "exactly TCP 80 and 443"),
        ("application management on all interfaces", set_env("application", "MANAGEMENT_SERVER_ADDRESS", "0.0.0.0"),
         "MANAGEMENT_SERVER_ADDRESS"),
        ("application trusts every forwarded header", set_env("application", "SERVER_FORWARD_HEADERS_STRATEGY", "FRAMEWORK"),
         "SERVER_FORWARD_HEADERS_STRATEGY"),
        ("application trusts any private peer", set_env("application", "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES", ".*"),
         "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES"),
        ("application issuer drifts", set_env("application", "OIDC_ISSUER_URI", f"{ISSUER}/"), "OIDC_ISSUER_URI"),
        ("application token endpoint bypasses TLS", set_env("application", "OIDC_TOKEN_URI",
         "http://keycloak:8080/realms/gameometro/protocol/openid-connect/token"), "OIDC_TOKEN_URI"),
        ("application admission enabled without calibration", set_env("application", "APPLICATION_API_ADMISSION_ENABLED", "true"),
         "APPLICATION_API_ADMISSION_ENABLED"),
        ("application cookie lacks __Host- prefix", set_env("application", "APPLICATION_SESSION_COOKIE_NAME", "vgp_session"),
         "APPLICATION_SESSION_COOKIE_NAME"),
        ("Keycloak hostname follows requests", lambda c: c["services"]["keycloak"]["environment"].pop("KC_HOSTNAME"),
         "KC_HOSTNAME"),
        ("Keycloak trusts any proxy", set_env("keycloak", "KC_PROXY_TRUSTED_ADDRESSES", "0.0.0.0/0"),
         "KC_PROXY_TRUSTED_ADDRESSES"),
        ("Keycloak HTTP access log enabled", set_env("keycloak", "KC_HTTP_ACCESS_LOG_ENABLED", "true"),
         "KC_HTTP_ACCESS_LOG_ENABLED"),
        ("Keycloak debug logging", set_env("keycloak", "KC_LOG_LEVEL", "debug"), "KC_LOG_LEVEL"),
        ("Keycloak loses an abuse bound", lambda c: c["services"]["keycloak"]["environment"].pop("KC_HTTP_MAX_QUEUED_REQUESTS"),
         "KC_HTTP_MAX_QUEUED_REQUESTS"),
        ("Keycloak imports the shared realm", bind("keycloak", str(ROOT / "docker/keycloak/import/videogame-platform-realm.json"),
         "/opt/keycloak/data/import/videogame-platform-realm.json"), "must not add files to the production import"),
        ("Keycloak joins the application edge", attach("keycloak", "application-edge"), "keycloak networks"),
        ("edge joins a data network", attach("caddy", "application-data"), "caddy networks"),
        ("application joins the identity data network", attach("application", "identity-data"), "application networks"),
        ("data network gains egress", lambda c: c["networks"]["application-data"].pop("internal"),
         "network application-data internal"),
        ("edge gate missing", lambda c: c["services"]["caddy"].__setitem__("volumes", [
            v for v in c["services"]["caddy"]["volumes"] if v.get("target") != "/run/recovery"]), "edge must read the recovery"),
        ("edge gate writable", lambda c: next(v for v in c["services"]["caddy"]["volumes"]
                                              if v.get("target") == "/run/recovery").__setitem__("read_only", False),
         "mount the recovery gate read-only"),
        ("BFF address becomes dynamic", lambda c: c["services"]["application"]["networks"].__setitem__("application-edge", None),
         "application on application-edge"),
        ("edge network gateway moves", lambda c: c["networks"]["application-edge"]["ipam"]["config"][0].__setitem__(
            "gateway", "10.203.1.14"), "network application-edge gateway"),
        ("edge from the stock image", lambda c: c["services"]["caddy"].__setitem__(
            "image", "caddy:2.11.7-alpine@sha256:" + "6" * 64), "caddy must run the CI-published GHCR digest"),
        ("edge address leaves the fixed subnet",
         lambda c: c["services"]["caddy"]["networks"]["application-edge"].__setitem__("ipv4_address", "10.203.1.9"),
         "caddy on application-edge"),
        ("application image uses a tag", lambda c: c["services"]["application"].__setitem__(
            "image", "ghcr.io/rubhern/videogame-platform:latest"), "application image must be referenced by sha256"),
        ("Keycloak image uses a tag", lambda c: c["services"]["keycloak"].__setitem__("image", "quay.io/keycloak/keycloak:26.7.0"),
         "keycloak image must be referenced by sha256"),
        ("application image from another registry", lambda c: c["services"]["application"].__setitem__(
            "image", "docker.io/example/app@sha256:" + "1" * 64), "CI-published GHCR digest of ghcr.io/rubhern/videogame-platform"),
        ("unthemed upstream Keycloak", lambda c: c["services"]["keycloak"].__setitem__(
            "image", "quay.io/keycloak/keycloak:26.7.4@sha256:" + "2" * 64), "keycloak must run the CI-published GHCR digest"),
        ("Keycloak tag on the trusted repository", lambda c: c["services"]["keycloak"].__setitem__(
            "image", "ghcr.io/rubhern/videogame-platform-keycloak:" + "a" * 40), "keycloak image must be referenced by sha256"),
        ("locally built localizer", lambda c: c["services"]["catalogue-localizer"].__setitem__(
            "image", "videogame-platform/catalogue-localizer:1.0.0"), "catalogue-localizer image must be referenced by sha256"),
        ("localizer from the application repository", lambda c: c["services"]["catalogue-localizer"].__setitem__(
            "image", "ghcr.io/rubhern/videogame-platform@sha256:" + "3" * 64), "catalogue-localizer must run the CI-published"),
        ("migration runs another digest", lambda c: c["services"]["migration"].__setitem__(
            "image", "ghcr.io/rubhern/videogame-platform@sha256:" + "5" * 64), "same image digest as the application"),
        ("application starts by default", lambda c: c["services"]["application"].pop("profiles"),
         "explicit Compose profiles"),
        ("application migrates at startup", set_env("application", "APPLICATION_FLYWAY_ENABLED", "true"),
         "APPLICATION_FLYWAY_ENABLED"),
        ("model directory created on the host", lambda c: next(
            volume for volume in c["services"]["catalogue-localizer"]["volumes"] if volume["target"] == "/model"
        ).setdefault("bind", {}).__setitem__("create_host_path", True), "model must be an existing read-only bind"),
        ("localizer builds on the host", lambda c: c["services"]["catalogue-localizer"].__setitem__(
            "build", {"context": "."}), "must not build on the production host"),
        ("inline database password", set_env("application", "APPLICATION_DB_PASSWORD", "synthetic"),
         "passes APPLICATION_DB_PASSWORD inline"),
        ("inline SMTP user", set_env("keycloak", "KEYCLOAK_SMTP_USER", "synthetic"), "passes KEYCLOAK_SMTP_USER inline"),
        ("secret path in arguments", lambda c: c["services"]["migration"].__setitem__(
            "command", ["--password-file", "/run/secrets/application_migration_db_password"]), "migration command"),
        ("application reads the identity database secret", grant("application", "keycloak_db_password"),
         "application secrets must be exactly"),
        ("edge receives a secret", grant("caddy", "keycloak_bff_client_secret"), "caddy secrets must be exactly"),
        ("secret from an environment variable", lambda c: c["secrets"]["igdb_client_secret"].update(
            {"environment": "IGDB_CLIENT_SECRET"}), "secret igdb_client_secret"),
        ("secret file outside the production directory", lambda c: c["secrets"]["igdb_client_id"].__setitem__(
            "file", f"{PRIVATE_DEV_SECRETS}/igdb-client-id"), "secret igdb_client_id"),
        ("secret directory mounted", bind("application", secrets_dir, "/secrets"),
         "must receive secrets as Compose secrets"),
        ("Docker socket mounted", bind("caddy", "/var/run/docker.sock", "/var/run/docker.sock"),
         "must not mount host runtime path"),
        ("writable bind mount", bind("application", str(RUNTIME / "caddy"), "/opt/edge", read_only=False),
         "must be read-only"),
        ("missing reviewed file mounted", bind("application", str(RUNTIME / "bin/run-absent"), "/opt/absent"),
         "does not exist in this checkout"),
        ("private-dev configuration mounted", bind("keycloak", str(ROOT / "deploy/private-dev/runtime.env.example"),
         "/opt/runtime.env"), "outside the production directory"),
        ("privileged container", lambda c: c["services"]["catalogue-localizer"].__setitem__("privileged", True),
         "must not be privileged"),
        ("host networking", lambda c: c["services"]["application"].__setitem__("network_mode", "host"),
         "must not set network_mode"),
        ("writable root filesystem", lambda c: c["services"]["keycloak"].__setitem__("read_only", False),
         "keycloak must use a read-only root"),
        ("capability added", lambda c: c["services"]["application"].__setitem__("cap_add", ["NET_ADMIN"]),
         "application may add only"),
        ("memory ceiling removed", lambda c: c["services"]["keycloak"].pop("mem_limit"), "keycloak needs a memory ceiling"),
        ("memory ceiling above the envelope", lambda c: c["services"]["application"].__setitem__("mem_limit", str(2048 * MIB)),
         "application needs a memory ceiling"),
        ("unbounded logs", lambda c: c["services"]["postgres"].__setitem__("logging", {"driver": "json-file"}),
         "postgres needs bounded local Docker logs"),
        ("project shares private-dev's name", lambda c: c.__setitem__("name", "videogame-platform-dev"), "project name"),
        ("unexpected service", lambda c: c["services"].__setitem__("adminer", copy.deepcopy(c["services"]["catalogue-localizer"])),
         "services must be exactly"),
    ]


CADDY_STATIC_MUTATIONS = [
    ("admin API enabled", lambda text: text.replace("\tadmin off\n", ""), "admin API must be disabled"),
    ("HTTP/3 enabled", lambda text: text.replace("protocols h1 h2", "protocols h1 h2 h3"), "only HTTP/1.1 and HTTP/2"),
    ("client proxy chain trusted", lambda text: text.replace("\t\tstrict_sni_host on\n",
     "\t\tstrict_sni_host on\n\t\ttrusted_proxies static private_ranges\n"), "must not trust a client-supplied proxy"),
    ("internal CA in production", lambda text: text.replace("\tadmin off\n", "\tadmin off\n\tlocal_certs\n"),
     "internal CA"),
    ("extra upstream", lambda text: text.replace("reverse_proxy application:8080", "reverse_proxy application:8081"),
     "must proxy only to application:8080"),
    ("metrics handler", lambda text: text.replace("\theader -Server\n", "\theader -Server\n\tmetrics /metrics\n", 1),
     "metrics handler"),
    ("forwarded header kept", lambda text: text.replace("\theader_up -Forwarded\n", ""), "must drop ['Forwarded']"),
    ("limiter metrics enabled", lambda text: text.replace("\tdisable_metrics\n", ""), "metrics must stay disabled"),
    ("limiter keyed on a header", lambda text: text.replace("key {remote_host}", "key {header.X-Forwarded-For}"),
     "must key on the TCP peer"),
    ("IPv6 sources not grouped", lambda text: text.replace("\tipv6_prefix 64\n", ""), "group IPv6 sources by /64"),
    ("limiter refusals logged", lambda text: text.replace("\tlog default {\n\t\texclude http.handlers.rate_limit\n\t}\n", ""),
     "must not log limiter refusals"),
    ("limiter keys logged", lambda text: text.replace("\tdisable_metrics\n", "\tdisable_metrics\n\tlog_key\n"),
     "must not log keys"),
    ("aggregate zone merged into the per-source handler", lambda text: text.replace(
        "\t\t\timport limiter_options\n\t\t}\n\t\trate_limit {\n\t\t\tzone aggregate-application {",
        "\t\t\tzone aggregate-application {"), "separate handlers"),
    ("gateway sources admitted", lambda text: text.replace("@unattributed remote_ip 10.203.1.1 10.203.2.1",
                                                            "@unattributed remote_ip 192.0.2.1"), "edge network gateways"),
    ("identity fence not read", lambda text: text.replace("try_files /IDENTITY-MAINTENANCE.json", "try_files /ABSENT.json"),
     "IDENTITY-MAINTENANCE.json gate"),
]
# Each mutation must make the real edge fail a named behavioural check.
CADDY_ROUTE_MUTATIONS = [
    ("identity allowlist opened", lambda text: re.sub(r"@identity path [^\n]+", "@identity path /*", text),
     "identity path refused"),
    ("non-canonical identity paths admitted", lambda text: text.replace("@noncanonical path_regexp (;|.+/$)",
                                                                         "@noncanonical path_regexp ^$"), "identity path refused"),
    ("management route opened", lambda text: text.replace("\trespond @management 404\n", ""),
     "application management refused"),
    ("client proxy chain trusted", lambda text: text.replace("\t\tstrict_sni_host on\n",
     "\t\tstrict_sni_host on\n\t\ttrusted_proxies static private_ranges\n"), "forwarding X-Forwarded-For"),
    ("Forwarded header kept", lambda text: text.replace("\theader_up -Forwarded\n", ""), "strips client forwarding"),
    ("strict SNI disabled", lambda text: text.replace("\t\tstrict_sni_host on\n", ""), "TLS name and Host must match"),
    ("unknown hosts answered", lambda text: text.replace("http:// {\n\tabort\n}", "http:// {\n\trespond 200\n}"),
     "unknown plain HTTP host refused"),
]


def expect_detected(label, violations, fragment, failures):
    if not any(fragment in violation for violation in violations):
        failures.append(f"deliberate violation not detected: {label} (expected '{fragment}', got {violations})")


def synthetic_inputs(directory):
    directory = pathlib.Path(directory)
    secrets_dir = directory / "secrets"
    model_dir = directory / "model"
    secrets_dir.mkdir(mode=0o750)
    model_dir.mkdir()
    markers = []
    for file_name in SECRET_FILES.values():
        marker = f"synthetic-{file_name}-{secrets.token_hex(8)}"
        markers.append(marker)
        (secrets_dir / file_name).write_text(marker + "\n")
        (secrets_dir / file_name).chmod(0o640)
    text = ENV_EXAMPLE.read_text()
    text = text.replace("/etc/gameometro/production/secrets", str(secrets_dir))
    text = re.sub(r"^CATALOGUE_TRANSLATION_MODEL_DIR=.*$", f"CATALOGUE_TRANSLATION_MODEL_DIR={model_dir}", text, flags=re.M)
    text = re.sub(r"^PRODUCTION_SECRETS_GID=.*$", f"PRODUCTION_SECRETS_GID={os.getgid()}", text, flags=re.M)
    env_file = directory / "runtime.env"
    env_file.write_text(text)
    return env_file, str(secrets_dir), markers


def env_value(env_file, key):
    for line in pathlib.Path(env_file).read_text().splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1]
    return ""


def placeholder_violations(text):
    return [f"{line.split('=', 1)[0]} still holds a placeholder"
            for line in text.splitlines() if not line.startswith("#") and PLACEHOLDER.search(line)]


def placeholder_self_test():
    """The example's five placeholders are refused; resolved trusted digests are not."""
    failures = []
    example = ENV_EXAMPLE.read_text()
    expected = {"KEYCLOAK_IMAGE", "CATALOGUE_LOCALIZER_IMAGE", "CADDY_IMAGE", "APPLICATION_IMAGE", "APPLICATION_VERSION"}
    found = {violation.split(" ", 1)[0] for violation in placeholder_violations(example)}
    if found != expected:
        failures.append(f"placeholder check found {sorted(found)} in the example, expected {sorted(expected)}")
    resolved = re.sub(r"@sha256:0{64}", "@sha256:" + "4" * 64, example).replace("REPLACE_WITH_IMAGE_VERSION", "0.40.0")
    if placeholder_violations(resolved):
        failures.append(f"placeholder check refused resolved values: {placeholder_violations(resolved)}")
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--env-file")
    parser.add_argument("--static-only", action="store_true")
    arguments = parser.parse_args()

    if arguments.env_file:
        env_file = pathlib.Path(arguments.env_file).resolve()
        secrets_dir = env_value(env_file, "PRODUCTION_SECRETS_DIR")
        violations = placeholder_violations(env_file.read_text()) + secrets_dir_violations(secrets_dir) \
            + compose_violations(render(env_file), secrets_dir) + wrapper_violations()
        for violation in violations:
            print(f"VIOLATION {violation}")
        print(f"Protected runtime configuration: {len(violations)} violation(s); no container was started.")
        return 1 if violations else 0

    failures = []
    build_edge_image()
    try:
        failures += contract(arguments)
    finally:
        subprocess.run(["docker", "image", "rm", "--force", EDGE_TEST_IMAGE], capture_output=True)
    for failure in failures:
        print(f"FAIL {failure}")
    if failures:
        return 1
    print("Public-production runtime contract passed with synthetic inputs. This is static and local "
          "evidence only: no DNS, ACME issuance, real host, firewall, Docker NAT or credential was exercised.")
    return 0


def build_edge_image():
    """The reviewed edge recipe, built locally for this test only; production pulls a CI digest."""
    run(["docker", "build", "--quiet", "--tag", EDGE_TEST_IMAGE, "--file", str(EDGE_DOCKERFILE),
         str(EDGE_DOCKERFILE.parent)])


def contract(arguments):
    failures = []
    with tempfile.TemporaryDirectory(prefix="public-production-runtime-") as directory:
        env_file, secrets_dir, markers = synthetic_inputs(directory)
        config = render(env_file)
        rendered = json.dumps(config)
        failures += compose_violations(config, secrets_dir) + env_example_violations(config) + wrapper_violations() \
            + placeholder_self_test()
        failures += [f"rendered configuration contains a secret value ({index})"
                     for index, marker in enumerate(markers) if marker in rendered]
        mutations = compose_mutations(secrets_dir)
        for label, mutate, fragment in mutations:
            mutated = copy.deepcopy(config)
            mutate(mutated)
            expect_detected(label, compose_violations(mutated, secrets_dir), fragment, failures)
        for label, directory_value in (("private-dev secrets", PRIVATE_DEV_SECRETS),
                                       ("repository secrets", str(ROOT / "secrets")), ("relative secrets", "secrets")):
            expect_detected(label, secrets_dir_violations(directory_value), "PRODUCTION_SECRETS_DIR", failures)
        print(f"Compose contract: {len(mutations) + 3} deliberate violations checked.")

        caddyfile = CADDYFILE.read_text()
        caddy_config = adapt(caddyfile, directory)
        failures += caddy_violations(caddy_config)
        for label, mutate, fragment in CADDY_STATIC_MUTATIONS:
            mutated = mutate(caddyfile)
            assert mutated != caddyfile, f"mutation '{label}' no longer applies to the Caddyfile"
            expect_detected(label, caddy_violations(adapt(mutated, directory)), fragment, failures)
        print(f"Caddy contract: {len(CADDY_STATIC_MUTATIONS)} deliberate violations checked.")

        if not arguments.static_only and not failures:
            with Edge(directory, env_file) as edge:
                edge.load(edge_test_config(caddy_config), gate="ready")
                for service in ("prober", "backchannel-prober"):
                    status, output = edge.probe(service)
                    print(output.strip().splitlines()[-1] if output.strip() else f"{service}: no output")
                    if status != 0:
                        failures.append(f"edge {service} failed:\n{output}")
                for label, mutate, fragment in CADDY_ROUTE_MUTATIONS:
                    mutated = mutate(caddyfile)
                    assert mutated != caddyfile, f"mutation '{label}' no longer applies to the Caddyfile"
                    edge.load(edge_test_config(adapt(mutated, directory)))
                    status, output = edge.probe("prober")
                    detected = [line for line in output.splitlines() if line.startswith("VIOLATION") and fragment in line]
                    if status == 0 or not detected:
                        failures.append(f"edge did not detect deliberate violation: {label} (expected '{fragment}')")
                print(f"Edge behaviour: {len(CADDY_ROUTE_MUTATIONS)} deliberate route violations checked.")
                failures += exercise_limits(edge, caddyfile, directory)
    return failures


if __name__ == "__main__":
    sys.exit(main())
