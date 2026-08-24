# Local backend dependencies

- **Status:** Active
- **Last verified:** 2026-08-23
- **Scope:** Disposable local PostgreSQL and Keycloak, with an optional complete application profile, on the supported WSL2 workstation
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)
- **Platform design:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

The default topology provides the backend dependencies approved for the walking
skeleton. The opt-in `full` profile adds the existing complete application image;
it does not add a separate frontend container or provision remote infrastructure.
The executable application connects through the documented persistence configuration
and OIDC profile.

## Topology

| Service or role | Executable version | Local address | Responsibility |
|---|---|---|---|
| PostgreSQL | `postgres:18.4-bookworm` | `127.0.0.1:5432` | One server containing isolated application and Keycloak databases |
| Keycloak | `quay.io/keycloak/keycloak:26.7.0` | `http://localhost:8180` | Local OIDC provider and private administration console |
| Keycloak management | Keycloak 26.7.0 | `127.0.0.1:9000` | Local health and metrics endpoints |
| Application (`full` profile only) | `videogame-platform/application:0.7.1-SNAPSHOT` | `http://localhost:8080` | Packaged React frontend, same-origin BFF/API and Spring Boot modular monolith |
| `videogame_app` | PostgreSQL login role | Application database only | Runtime DML role; it cannot create schema objects |
| `videogame_app_migrator` | PostgreSQL login role | Application database only | Owns the application database and is reserved for Flyway migrations |
| `videogame_keycloak` | PostgreSQL login role | Keycloak database only | Owns and operates the Keycloak database |

All published host ports bind to loopback; they are not exposed to the LAN. Compose
limits the default dependency topology to 1.5 CPUs and 1.5 GiB of memory. The `full`
profile limits the complete topology to 2 CPUs and 2.5 GiB, within the approved
private `dev` CPU and memory ceilings. `bash scripts/validate-topology-budget.sh`
renders and checks those limits without starting the topology. These are compatibility
limits, not production sizing or capacity planning.

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

When the root `.env` is absent, the wrapper creates it with mode `0600`, independent
random database passwords, an administration password, a confidential-client secret,
and a local test-user password. It also creates `backend/.env` with the complete
application configuration and copies the runtime-role password, migration-role
password, and BFF client secret that the backend shares with the provisioned
infrastructure. Both files are ignored by Git. The corresponding `.env.example`
files document their separate scopes with non-secret placeholders; copying those
placeholders is not required. The wrapper reapplies mode `0600` when it handles an
existing local environment file.

The imported identity configuration is:

| Setting | Local value |
|---|---|
| Realm / issuer | `videogame-platform` / `http://localhost:8180/realms/videogame-platform` |
| Confidential client | `videogame-platform-bff` |
| Authorization flow | Authorization Code with PKCE `S256` |
| Redirect URI | `http://localhost:8080/login/oauth2/code/keycloak` |
| Direct password grant | Disabled |
| Local username | `LOCAL_TEST_USER_USERNAME` from root `.env` |
| Local user password | `LOCAL_TEST_USER_PASSWORD` from root `.env` |
| Client secret | `KEYCLOAK_BFF_CLIENT_SECRET` from root `.env` |

The disposable user also has the synthetic non-routable profile
`Local User <local-user@localhost.invalid>` so Keycloak 26.7 does not interrupt the
automated compatibility login with `VERIFY_PROFILE`. It is test data, not a product
identity or personal account.

Keycloak substitutes the ignored environment values into the reviewed realm file at
first import. The repository therefore contains the reproducible realm, client, and
user shape but no usable password or client secret.

After the wrapper has created the ignored `.env`, the two direct Compose modes are:

```bash
# PostgreSQL and Keycloak only (the existing default)
docker compose up

# PostgreSQL, Keycloak and the complete application image
docker compose --profile full up --build
```

The `application` service is profile-gated, so it is not selected by the first
command. Compose waits for both dependencies to become healthy before it starts the
application. The application then exposes its own readiness health check.

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

Direct host execution loads these application-side values from `backend/.env`. The
`full` Compose profile reads the same three secret values from the ignored root
`.env`, changes both JDBC hosts to `postgres`, and keeps the usernames and database
names unchanged. PostgreSQL and Keycloak must provision credentials that exactly
match the application clients.

For OIDC, the application sends the browser to the public issuer and authorization
endpoint under `http://localhost:8180`. Server-only token exchange, key retrieval and
UserInfo calls use `http://keycloak:8080` on the Compose network. ID tokens are still
required to contain the exact public issuer; the internal connection does not weaken
issuer, signature, audience, lifetime or nonce validation.

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

# Verify the complete application/PostgreSQL/Keycloak CPU and memory limits
bash scripts/validate-topology-budget.sh

# Follow dependency logs
bash scripts/local-dependencies.sh logs

# Stop containers and preserve data
bash scripts/local-dependencies.sh down
```

The image-manifest check was executed successfully again on 2026-08-23 for
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
`S256`, both exact local/CI callbacks, rejection of an unlisted callback, and the
environment-backed local user's password credential. It prints no secret value.

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
startup of the complete application is proven separately by the container-image
gate; this command owns only the dependency manifests.

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
command, preventing a modified root `.env` from targeting another Compose project. It
does not delete either `.env`, repository files, images, unrelated volumes, or remote data. Run
`up` afterwards to recreate both databases and re-import the current realm definition.
Reset is required when testing a changed initialization script or realm import against
fresh state.

## Security and supported boundary

- The topology uses Keycloak development mode and plain HTTP on loopback; it is not a
  remote or production configuration.
- Passwords and the OIDC client secret exist only in the ignored root/backend `.env`
  files and the local container runtime. Do not paste them into logs, issues, commits,
  or documentation.
- Changing a shared credential requires the same value in both ignored files and does
  not rotate credentials already stored in the persistent volume. Reset disposable
  data, or use an explicit rotation procedure once persistent environments exist.
- The bootstrap administrator is for local administration only. Remote `dev` requires
  its own protected secret source, HTTPS, private ingress, backups, and operational
  configuration from the approved platform design.

## Deliberate issue boundaries

This topology remains the dependency contract:

- Flyway migrations, JPA configuration, the initial catalogue schema, deterministic
  catalogue data, and Testcontainers persistence evidence are implemented and
  documented separately in the database migration guide.
- Authorization Code exchange, opaque application sessions, CSRF protection,
  logout, and browser-level BFF compatibility evidence are implemented by issue #40
  and documented in the [identity guide](identity-bff.md).
- Pull-request CI integration is implemented by issue #24; the commands here are
  reused by that gate without creating a second dependency definition.

Keeping those boundaries explicit avoids presenting a healthy dependency container
alone as application authentication evidence; `validate-identity.sh` owns the
end-to-end proof.
