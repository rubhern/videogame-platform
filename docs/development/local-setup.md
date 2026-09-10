# Local setup

The supported environment is Ubuntu 24.04 on WSL2, with the repository below
`/home`, Java 25, Node.js 24/npm 11, Git, and Docker Desktop's WSL integration. A
clone under `/mnt/c`, a second Docker daemon inside Ubuntu, or Docker that requires
`sudo` is outside the verified boundary.

## Prerequisites

Install a complete Java 25 JDK and Node.js 24 using their official distribution
instructions. Keep exact patch versions out of this guide; repository manifests and
the prerequisite script own compatibility.

```bash
bash scripts/validate-prerequisites.sh
```

The script checks WSL/filesystem placement, Java/Javac, Node/npm, Python, Git,
Docker, Compose, and the Maven Wrapper without starting services. Use its
diagnostics rather than maintaining a parallel checklist here.

## Local topology

The default Compose topology provides loopback-only PostgreSQL and Keycloak. The
`full` profile adds the packaged application; the frontend is embedded, not a
separate container.

| Service | Address | Notes |
|---|---|---|
| PostgreSQL | `127.0.0.1:5432` | Separate application and Keycloak databases/roles |
| Keycloak | `http://localhost:8180` | Development-mode OIDC provider |
| Keycloak management | `127.0.0.1:9000` | Local health and metrics |
| Application (`full` only) | `http://localhost:8080` | Frontend + BFF/API + modular monolith |
| Application management | `127.0.0.1:8081` | Local Actuator health, info, and metrics |

Exact images, health checks, ports, resources, and wiring are authoritative in
[`compose.yaml`](../../compose.yaml). `.env.example` and `backend/.env.example` own
configuration names and safe placeholders.

### Configuration sources and precedence

Backend application configuration has one local source, `backend/.env`, used by both
direct execution and the packaged container:

- Root `.env` holds only Compose/infrastructure wiring and the shared generated
  secrets Compose provisions (database and Keycloak passwords, the BFF client secret,
  ports, hostnames, the local test user).
- `backend/.env` holds the backend application configuration (database URL and
  credentials, OIDC issuer, catalogue windows and policy, IGDB synchronization,
  timeouts, session, logging, telemetry). `./mvnw -pl backend spring-boot:run` loads
  it with `source backend/.env`; the Compose `application` service loads the same file
  with `env_file`.
- The Compose `application` service adds explicit `environment` values only where the
  container genuinely differs from host execution — database and Keycloak container
  addresses, the internal token/JWKS/userinfo endpoints, the management bind address,
  the mandatory `oidc` profile, and packaged Flyway execution — plus the shared
  generated secrets from root `.env`. Compose `environment` overrides `env_file`, so a
  container-specific value always wins while everything else comes from `backend/.env`.

Because both runtimes read `backend/.env`, changing a value there (for example a
catalogue window or IGDB credentials) affects `spring-boot:run` and
`scripts/local-dependencies.sh application` alike, with no duplication in root `.env`
or `compose.yaml`. The packaged application publishes its management port only on host
loopback (`127.0.0.1:8081`), so the operator `/actuator/cataloguesync` endpoint stays
usable locally without network exposure.

## Start, verify, and stop

```bash
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify
bash scripts/local-dependencies.sh status
bash scripts/local-dependencies.sh down
```

On first `up`, the wrapper creates ignored `.env` files with independent random
credentials and mode `0600`. It never prints secret values. To verify the approved
image architectures or topology budget:

```bash
bash scripts/local-dependencies.sh verify-images
bash scripts/validate-topology-budget.sh
```

Run the complete application with:

```bash
bash scripts/local-dependencies.sh application
```

For separate development loops, use the commands in the
[backend README](../../backend/README.md) and
[frontend README](../../frontend/README.md).

## Disposable reset

The named PostgreSQL volume persists through normal `down`/`up`. Reset only after
confirming it contains disposable project-local data:

```bash
bash scripts/local-dependencies.sh reset
```

`reset --yes` is reserved for an explicitly disposable non-interactive environment.
The wrapper validates the fixed Compose project name and removes only that project's
containers, network, and PostgreSQL volume; it does not delete repository files,
images, `.env` files, unrelated volumes, or remote data.

Keycloak runs over loopback HTTP and uses a non-personal synthetic test account.
The imported realm enables Keycloak-hosted self-registration, so a new visitor can
create an account from the Keycloak login page without administrator provisioning;
the realm import owns this setting, so applying it to an already-provisioned instance
needs a `reset` and re-import. Remote environments require private HTTPS, separate
secrets, backups, and the platform controls; local settings are not production
defaults.
