"""Regression test for the shared PostgreSQL bootstrap (docker/postgres/init).

Initializes disposable PostgreSQL 18 containers with no network through both secret
transports the environments use: *_FILE secret files (private-dev and public production)
and direct variables (local development and CI). A recording psql shim captures every
psql command line. It then checks:

- no role password appears in any psql argument, in the container log, or in the
  environment Compose would show;
- the three roles exist with their contracted attributes, and each logs in with its
  own password over SCRAM;
- databases, ownership and CONNECT grants match the contract.

A copy of the bootstrap with one password-bearing psql argument must fail, proving the
exposure check detects that regression. Nothing is published and no environment data is used.
"""

import pathlib
import secrets
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
INIT = ROOT / "docker/postgres/init"
POSTGRES_IMAGE = "postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382"
# Earlier in PATH than /usr/bin/psql; records the argument vector, then runs the real client.
SHIM = """#!/bin/sh
printf '%s\\n' "$*" >> /probe/psql-arguments
exec /usr/bin/psql "$@"
"""
ROLES = {
    "videogame_app": "APPLICATION_DB_PASSWORD",
    "videogame_app_migrator": "APPLICATION_MIGRATION_DB_PASSWORD",
    "videogame_keycloak": "KEYCLOAK_DB_PASSWORD",
}
CONTRACT_SQL = """
SELECT string_agg(format('%s:%s:%s:%s:%s', rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole), ',' ORDER BY rolname)
  FROM pg_roles WHERE rolname LIKE 'videogame_%'
UNION ALL
SELECT string_agg(format('%s:%s', datname, pg_get_userbyid(datdba)), ',' ORDER BY datname)
  FROM pg_database WHERE datname LIKE 'videogame_%'
UNION ALL
SELECT string_agg(format('%s:%s:%s', r, d, has_database_privilege(r, d, 'CONNECT')), ',' ORDER BY r, d)
  FROM unnest(ARRAY['videogame_app', 'videogame_app_migrator', 'videogame_keycloak']) r,
       unnest(ARRAY['videogame_platform', 'videogame_keycloak']) d
"""
EXPECTED_CONTRACT = [
    "videogame_app:t:f:f:f,videogame_app_migrator:t:f:f:f,videogame_keycloak:t:f:f:f",
    "videogame_keycloak:videogame_keycloak,videogame_platform:videogame_app_migrator",
    "videogame_app:videogame_keycloak:f,videogame_app:videogame_platform:t,"
    "videogame_app_migrator:videogame_keycloak:f,videogame_app_migrator:videogame_platform:t,"
    "videogame_keycloak:videogame_keycloak:t,videogame_keycloak:videogame_platform:f",
]


def docker(*arguments, check=True):
    return subprocess.run(["docker", *arguments], check=check, text=True, capture_output=True)


def bootstrap(init_dir, transport):
    """Initialize one container; return a list of contract violations."""
    passwords = {variable: f"bootstrap-probe-{secrets.token_hex(12)}" for variable in ROLES.values()}
    admin_password = secrets.token_hex(12)
    name = f"videogame-platform-bootstrap-test-{secrets.token_hex(4)}"
    with tempfile.TemporaryDirectory(prefix="postgres-bootstrap-") as directory:
        probe = pathlib.Path(directory, "probe")
        probe.mkdir()
        probe.chmod(0o777)
        shim = pathlib.Path(directory, "psql")
        shim.write_text(SHIM)
        shim.chmod(0o755)
        options = ["--env", f"POSTGRES_PASSWORD={admin_password}",
                   "--env", "POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=peer"]
        if transport == "file":
            secret_dir = pathlib.Path(directory, "secrets")
            secret_dir.mkdir()
            for variable, value in passwords.items():
                (secret_dir / variable).write_text(value + "\n")
                (secret_dir / variable).chmod(0o644)
                options += ["--env", f"{variable}_FILE=/run/secrets/{variable}"]
            options += ["--volume", f"{secret_dir}:/run/secrets:ro"]
        else:
            for variable, value in passwords.items():
                options += ["--env", f"{variable}={value}"]
        docker("run", "--detach", "--name", name, "--network", "none", *options,
               "--volume", f"{init_dir}:/docker-entrypoint-initdb.d:ro",
               "--volume", f"{shim}:/usr/local/bin/psql:ro",
               "--volume", f"{probe}:/probe", POSTGRES_IMAGE)
        try:
            for _ in range(90):
                log = docker("logs", name, check=False)
                if "PostgreSQL init process complete" in log.stdout + log.stderr:
                    break
                if docker("inspect", "--format", "{{.State.Running}}", name, check=False).stdout.strip() != "true":
                    return [f"{transport}: bootstrap exited: {(log.stdout + log.stderr)[-800:]}"]
                time.sleep(1)
            else:
                return [f"{transport}: bootstrap did not complete"]
            for _ in range(30):
                if docker("exec", name, "pg_isready", "--host=127.0.0.1", "--quiet", check=False).returncode == 0:
                    break
                time.sleep(1)
            return contract_violations(name, transport, passwords, probe)
        finally:
            docker("rm", "--force", name, check=False)


def contract_violations(name, transport, passwords, probe):
    found = []
    arguments = (probe / "psql-arguments").read_text() if (probe / "psql-arguments").exists() else ""
    if "videogame_app" not in arguments and "ON_ERROR_STOP" not in arguments:
        found.append(f"{transport}: the psql shim recorded no bootstrap invocation")
    log = docker("logs", name, check=False)
    inspected = docker("inspect", "--format", "{{json .Config.Env}} {{json .Args}}", name).stdout
    for variable, value in passwords.items():
        if value in arguments:
            found.append(f"{transport}: {variable} appeared in a psql command line")
        if value in log.stdout + log.stderr:
            found.append(f"{transport}: {variable} appeared in the container log")
        if transport == "file" and value in inspected:
            found.append(f"{transport}: {variable} appeared in container metadata")
    contract = docker("exec", name, "gosu", "postgres", "psql", "--dbname=postgres", "--tuples-only",
                      "--no-align", "--command", CONTRACT_SQL, check=False).stdout.split()
    if contract != EXPECTED_CONTRACT:
        found.append(f"{transport}: role/database contract differs: {contract}")
    for role, variable in ROLES.items():
        database = "videogame_keycloak" if role == "videogame_keycloak" else "videogame_platform"
        login = docker("exec", "--env", f"PGPASSWORD={passwords[variable]}", name, "psql", "--host=127.0.0.1",
                       f"--username={role}", f"--dbname={database}", "--tuples-only", "--no-align",
                       "--command", "SELECT current_user", check=False)
        if login.stdout.strip() != role:
            found.append(f"{transport}: {role} cannot log in with its password")
        wrong = docker("exec", "--env", "PGPASSWORD=wrong-password", name, "psql", "--host=127.0.0.1",
                       f"--username={role}", f"--dbname={database}", "--command", "SELECT 1", check=False)
        if wrong.returncode == 0:
            found.append(f"{transport}: {role} accepted a wrong password")
    return found


def main():
    failures = []
    for transport in ("file", "variable"):
        failures += bootstrap(INIT, transport)
    print(f"Bootstrap contract: {'passed' if not failures else 'FAILED'} for file and variable transports.")

    # Negative control: reintroduce one password-bearing psql argument into the current script.
    original = (INIT / "001-create-databases.sh").read_text()
    anchor = "psql --set=ON_ERROR_STOP=1 \\\n"
    if original.count(anchor) != 1:
        failures.append("the negative control can no longer locate the bootstrap psql invocation")
    else:
        with tempfile.TemporaryDirectory(prefix="postgres-bootstrap-leak-") as directory:
            script = pathlib.Path(directory, "001-create-databases.sh")
            script.write_text(original.replace(anchor, anchor + '  --set=leak="$APPLICATION_DB_PASSWORD" \\\n'))
            script.chmod(0o755)
            pathlib.Path(directory).chmod(0o755)
            detected = bootstrap(pathlib.Path(directory), "file")
            if not any("APPLICATION_DB_PASSWORD appeared in a psql command line" in item for item in detected):
                failures.append(f"the exposure check missed a password-bearing psql argument: {detected}")
            else:
                print("Negative control: a password-bearing psql argument was detected.")

    for failure in failures:
        print(f"FAIL {failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
