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
separate container. The optional observability overlay adds the same bounded metrics
stack and provisioned dashboards used by private dev, with independent local volumes
and credentials.

| Service | Address | Notes |
|---|---|---|
| PostgreSQL | `127.0.0.1:5432` | Separate application and Keycloak databases/roles |
| Keycloak | `http://localhost:8180` | Development-mode OIDC provider |
| Keycloak management | `127.0.0.1:9000` | Local health and metrics |
| Application (`full` only) | `http://localhost:8080` | Frontend + BFF/API + modular monolith |
| Application management | `127.0.0.1:8081` | Local Actuator health, info, and metrics |
| Grafana (optional) | `http://127.0.0.1:3000` | Authenticated dashboards; no SSH tunnel needed locally |
| Collector OTLP HTTP (optional) | `127.0.0.1:4318` | Host/IDE backend export only; Prometheus and the scrape handoff remain internal |

Exact images, health checks, ports, resources, and wiring are authoritative in
[`compose.yaml`](../../compose.yaml) and its optional
[observability overlay](../../compose.observability.yaml). `.env.example` and `backend/.env.example` own
configuration names and safe placeholders.

### Configuration sources

Root `.env` holds only Compose/infrastructure wiring and the generated shared secrets
(database and Keycloak passwords, the BFF client secret, ports, hostnames, the local
test user). `backend/.env` holds the backend application configuration and is the
single source for both `./mvnw -pl backend spring-boot:run` (`source backend/.env`)
and the Compose `application` service (`env_file`). Compose adds explicit
`environment` values only where the container genuinely differs from host execution
(container addresses, internal OIDC endpoints, management bind address, the `oidc`
profile, packaged Flyway execution); `environment` overrides `env_file`, so a
container-specific value always wins. Explicit `--observability` startup also enables
metrics export for that packaged invocation, without changing `backend/.env`. The packaged application publishes its
management port only on host loopback (`127.0.0.1:8081`).

## Start, verify, and stop

```bash
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify
bash scripts/local-dependencies.sh status
bash scripts/local-dependencies.sh down
```

On first `up`, the wrapper creates ignored `.env` files with independent random
credentials and mode `0600`. It never prints secret values. To verify the approved
image architectures:

```bash
bash scripts/local-dependencies.sh verify-images
```

Run the complete application with:

```bash
bash scripts/local-dependencies.sh application
```

If Keycloak reports `UnknownHostException: postgres`, check that its container and
PostgreSQL share the Compose network. A stale or missing network attachment can be
recovered with `down` followed by `application` from the same checkout and environment;
this recreates containers while retaining the named database volumes. Do not reset
identity data to fix a network-resolution failure. `verify` checks the configured
synthetic account; additional self-registered accounts do not invalidate that check.

For separate development loops, use the commands in the
[backend README](../../backend/README.md) and
[frontend README](../../frontend/README.md).

## Local metrics and dashboards

The overlay extends the [shared service definitions](../../deploy/private-dev/compose.observability.yaml),
so images, bounds, retention, Collector/Prometheus configuration and Grafana
provisioning have one executable owner. It adds only the local application wiring
and loopback OTLP ingress. Local metrics and credentials never use private-dev state.

### Packaged application with metrics

1. Start Docker Desktop with integration enabled for the WSL distribution, then run
   the prerequisite check above from the repository under `/home`.
2. Start the complete application and metrics stack:

   ```bash
   bash scripts/local-dependencies.sh application --observability
   ```

   This creates missing ignored environment files and a Grafana password, builds the
   packaged application and runs the stack in the foreground. Keep the terminal open.
   Existing backend settings and secrets are preserved. No IGDB synchronization or
   data seeding is triggered by enabling observability.
3. Open the application at `http://localhost:8080` and Grafana at
   `http://127.0.0.1:3000`. Sign in to Grafana as `owner`, reading the password from
   `.local-secrets/grafana-admin-password` in a local editor. Do not paste it into
   issues or commit the file. Open the **VideoGame Platform** folder; the datasource
   and all three dashboards are already provisioned.
4. Generate normal application traffic and wait a few export/scrape intervals. In a
   second terminal, check the stack:

   ```bash
   bash scripts/local-dependencies.sh status
   bash scripts/local-dependencies.sh verify-observability
   ```

   Verification checks running bounds, authentication, provisioning and query
   execution. It reports missing application data rather than inventing samples.
   Synchronization panels need a real, explicitly initiated synchronization; they
   may legitimately be empty. See [dashboard interpretation](observability.md#private-dev-dashboards)
   for counter, caching and product-learning limitations.
5. Stop the foreground command with Ctrl+C, then stop/remove the local containers
   while retaining their volumes:

   ```bash
   bash scripts/local-dependencies.sh down
   ```

   Start again with the same command in step 2. Grafana provisioning and retained
   metrics survive normal stop/start. Do not delete or regenerate the password file:
   Grafana's initialized database retains the original password.

### Backend from an IDE or Maven

Start dependencies plus metrics with `bash scripts/local-dependencies.sh up --observability`,
or add just the metrics stack to already-running dependencies with
`bash scripts/local-dependencies.sh observability`.

For the existing [backend development command](../../backend/README.md), load
`backend/.env` as usual and then set `TELEMETRY_OTLP_METRICS_ENABLED=true` and
`TELEMETRY_OTLP_METRICS_ENDPOINT=http://localhost:4318/v1/metrics` in that process
(or its IDE run configuration). Keep the other backend configuration, including
migration and identity settings, as documented by the backend README. The packaged
application uses the internal Collector hostname automatically; host execution uses
loopback. Do not run both backends on port 8080 simultaneously.

Local `down`, `status` and `reset` include the optional metrics services. Port 3000 or
4318 already occupied means the local startup must resolve that conflict; it must
never silently fall back to a public bind. Access on private dev still uses the
[owner SSH tunnel](../../deploy/private-dev/README.md#metrics-dashboards).

## Disposable reset

The named PostgreSQL, Prometheus and Grafana volumes persist through normal
`down`/`up`. Reset only after
confirming it contains disposable project-local data:

```bash
bash scripts/local-dependencies.sh reset
```

`reset --yes` is reserved for an explicitly disposable non-interactive environment.
The wrapper validates the fixed Compose project name and removes only that project's
containers, networks, PostgreSQL volume and optional metrics/Grafana volumes; it does
not delete repository files, images, `.env` files, `.local-secrets`, unrelated volumes
or remote data.

Keycloak runs over loopback HTTP and uses a non-personal synthetic test account.
The local and private-dev images build the lightweight `gameometro` theme from
[`Dockerfile`](../../deploy/private-dev/keycloak/Dockerfile), copying brand, fonts and
art from their canonical frontend sources. After changing theme sources, rebuild the
Keycloak service and recreate its container; inherited Keycloak templates own credential
forms and validation. Existing realms are not overwritten by startup import. Set the
`gameometro` Login theme, `Gameómetro` display name and supported/default Spanish
locale in Realm Settings, and enable Forgot password in Login
settings, or reset only a verified disposable local database to re-import the configuration.
Recovery rendering is available; email delivery requires operator-configured SMTP in
Keycloak. No SMTP service or paid resource is provisioned by this repository.
The shared imported realm contains environment-neutral identity/client policy; its
origins come from `APPLICATION_PUBLIC_ORIGIN`. A separate local-only user import adds
the synthetic account and is not mounted by private dev. The realm enables
Keycloak-hosted self-registration, so a new visitor can
create an account directly from the product header through the themed Keycloak
registration flow, or follow the registration link on its sign-in screen, without administrator provisioning;
the realm import owns this setting, so applying it to an already-provisioned instance
needs an explicit update in Realm Settings, or a reset of a disposable local database.
For `KEYCLOAK_BFF_CLIENT_SECRET`, the
realm keeps the secret it was imported with, so a regenerated `.env` (for example a
fresh worktree sharing the same Compose project) makes every login return to the game
as "not completed" until the stored secret and `.env` agree again; `verify` reports
this mismatch explicitly. Remote environments require private HTTPS, separate
secrets, backups, and the platform controls; local settings are not production
defaults.
