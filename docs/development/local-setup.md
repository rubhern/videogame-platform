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
Remote environments require private HTTPS, separate secrets, backups, and the
platform controls; local settings are not production defaults.
