# Local backend dependencies

- **Status:** Active
- **Last verified:** 2026-08-08
- **Scope:** Disposable local PostgreSQL and Keycloak topology on the supported WSL2 workstation
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)
- **Platform design:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

This topology provides the backend dependencies approved for the walking skeleton. It
does not add the application or frontend containers, implement a product feature, or
provision remote infrastructure. The executable application skeleton exists
separately and deliberately does not connect to these services until the focused
persistence and BFF identity work implements those contracts.

## Topology

| Service or role | Executable version | Local address | Responsibility |
|---|---|---|---|
| PostgreSQL | `postgres:18.4-bookworm` | `127.0.0.1:5432` | One server containing isolated application and Keycloak databases |
| Keycloak | `quay.io/keycloak/keycloak:26.7.0` | `http://localhost:8180` | Local OIDC provider and private administration console |
| Keycloak management | Keycloak 26.7.0 | `127.0.0.1:9000` | Local health and metrics endpoints |
| `videogame_app` | PostgreSQL login role | Application database only | Runtime DML role; it cannot create schema objects |
| `videogame_app_migrator` | PostgreSQL login role | Application database only | Owns the application database and is reserved for Flyway migrations |
| `videogame_keycloak` | PostgreSQL login role | Keycloak database only | Owns and operates the Keycloak database |

All published host ports bind to loopback; they are not exposed to the LAN. Compose
limits the topology to 1.5 CPUs and 1.5 GiB of memory in total, below the approved
private `dev` ceiling of 2 CPUs and 12 GiB. These are development limits, not
production sizing.

PostgreSQL must become healthy before Compose starts Keycloak. Keycloak then initializes
its own schema, imports the realm, and becomes healthy only when its readiness endpoint
returns HTTP 200.

Host connections authenticate with SCRAM-SHA-256. Local Unix-socket administration
inside the PostgreSQL container uses the image's explicitly configured trusted local
boundary so lifecycle verification does not place the administrator password on a
command line.

## First startup

Run this from the repository root:

```bash
bash scripts/local-dependencies.sh up
```

When `.env` is absent, the wrapper creates it with mode `0600`, independent random
database passwords, an administration password, a confidential-client secret, and a
local test-user password. `.env` is ignored by Git. `.env.example` documents every
variable using non-secret placeholders; copying those placeholders is not required.
The wrapper reapplies mode `0600` before every lifecycle command if the file already
exists.

The imported identity configuration is:

| Setting | Local value |
|---|---|
| Realm / issuer | `videogame-platform` / `http://localhost:8180/realms/videogame-platform` |
| Confidential client | `videogame-platform-bff` |
| Authorization flow | Authorization Code with PKCE `S256` |
| Redirect URI | `http://localhost:8080/login/oauth2/code/keycloak` |
| Direct password grant | Disabled |
| Local username | `LOCAL_TEST_USER_USERNAME` from `.env` |
| Local user password | `LOCAL_TEST_USER_PASSWORD` from `.env` |
| Client secret | `KEYCLOAK_BFF_CLIENT_SECRET` from `.env` |

Keycloak substitutes the ignored environment values into the reviewed realm file at
first import. The repository therefore contains the reproducible realm, client, and
user shape but no usable password or client secret.

## Backend connection contract

The backend persistence integration uses:

```text
Runtime JDBC URL:  jdbc:postgresql://localhost:5432/videogame_platform
Runtime username:  videogame_app
Runtime password:  APPLICATION_DB_PASSWORD

Flyway JDBC URL:   jdbc:postgresql://localhost:5432/videogame_platform
Flyway username:   videogame_app_migrator
Flyway password:   APPLICATION_MIGRATION_DB_PASSWORD

OIDC issuer:       http://localhost:8180/realms/videogame-platform
OIDC client ID:    videogame-platform-bff
OIDC client secret: KEYCLOAK_BFF_CLIENT_SECRET
```

The application runtime role is deliberately not the database owner and has no DDL
permission. Migrations created by `videogame_app_migrator` grant the standard table
and sequence DML privileges to `videogame_app` through PostgreSQL default privileges.
The initial `catalogue` migration repeats the schema-specific usage and default table
DML grants. The application role can read and modify catalogue tables but cannot
create or alter schema objects. See the
[application database migration guide](database-migrations.md) for the schema,
immutable migration policy, opt-in seed, validation, and startup procedure.

## Commands and verification

```bash
# Show current container and health state
bash scripts/local-dependencies.sh status

# Verify versions, health, database isolation, realm, BFF client and local user
bash scripts/local-dependencies.sh verify

# Verify both image manifests contain linux/amd64 and linux/arm64
bash scripts/local-dependencies.sh verify-images

# Follow dependency logs
bash scripts/local-dependencies.sh logs

# Stop containers and preserve data
bash scripts/local-dependencies.sh down
```

The image-manifest check was executed successfully on 2026-08-08 for
`postgres:18.4-bookworm` and `quay.io/keycloak/keycloak:26.7.0`. Both advertised
`linux/amd64` and `linux/arm64` variants. This check requires registry access and
should be rerun whenever either exact image reference changes.

For an ephemeral CI job, the same wrapper can be reused:

```bash
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify
bash scripts/local-dependencies.sh down
```

The wrapper uses `docker compose up --detach --wait`, so a failed health check returns
non-zero instead of racing dependent tests.

Runtime verification checks the actual PostgreSQL and Keycloak version lines, both
role passwords, database ownership and cross-database isolation, the OIDC discovery
document, acceptance of the exact callback URI, confidential-client flags, PKCE
`S256`, and the environment-backed local user's password credential. It prints no
secret value.

## Verified acceptance evidence

The following sequences were executed successfully on the supported WSL2 workstation
on 2026-08-08:

```bash
# Fresh-state proof: deletes only disposable project data
bash scripts/local-dependencies.sh reset --yes
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify

# Persistence proof: normal shutdown keeps the database volume
bash scripts/local-dependencies.sh down
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify

# Registry-manifest proof
bash scripts/local-dependencies.sh verify-images
```

The fresh-state proof created both databases and all three least-privilege roles,
initialized Keycloak's schema, imported the realm/client/user configuration, and
reached healthy status. The persistence proof reached the same verified state without
re-running destructive initialization. The manifest proof found `linux/amd64` and
`linux/arm64` variants for both exact dependency images. Native or emulated ARM64
startup of the complete application remains part of the broader compatibility gate,
not this dependency-manifest criterion.

## Persistence and reset

PostgreSQL data, including the Keycloak realm, is stored in the project-scoped named
volume `videogame-platform_postgres-data`. Normal `down` and subsequent `up` commands
preserve it. PostgreSQL initialization scripts and Keycloak startup imports run only
for new state; Keycloak deliberately skips a realm that already exists.

Use reset only for disposable local data:

```bash
# Interactive confirmation
bash scripts/local-dependencies.sh reset

# Explicit non-interactive form for disposable CI data
bash scripts/local-dependencies.sh reset --yes
```

Reset executes Compose removal only for the project named by `COMPOSE_PROJECT_NAME`
and deletes that project's containers, network, and named PostgreSQL volume. The
wrapper requires the reviewed project name `videogame-platform` before any lifecycle
command, preventing a modified `.env` from targeting another Compose project. It does
not delete `.env`, repository files, images, unrelated volumes, or remote data. Run
`up` afterwards to recreate both databases and re-import the current realm definition.
Reset is required when testing a changed initialization script or realm import against
fresh state.

## Security and supported boundary

- The topology uses Keycloak development mode and plain HTTP on loopback; it is not a
  remote or production configuration.
- Passwords and the OIDC client secret exist only in the ignored `.env` and the local
  container runtime. Do not paste them into logs, issues, commits, or documentation.
- Changing credentials in `.env` does not rotate credentials already stored in the
  persistent volume. Reset disposable data, or use an explicit rotation procedure
  once persistent environments exist.
- The bootstrap administrator is for local administration only. Remote `dev` requires
  its own protected secret source, HTTPS, private ingress, backups, and operational
  configuration from the approved platform design.

## Deliberate issue boundaries

This topology remains the dependency contract:

- Flyway migrations, JPA configuration, the initial catalogue schema, deterministic
  catalogue data, and Testcontainers persistence evidence are implemented and
  documented separately in the database migration guide.
- Authorization Code exchange, opaque application sessions, CSRF protection, logout,
  and browser-level BFF compatibility evidence belong to issue #40.
- Pull-request CI integration is implemented by issue #24; the commands here are
  reused by that gate without creating a second dependency definition.

Keeping those boundaries explicit avoids presenting a healthy dependency container as
evidence that application persistence or authentication is already implemented.
