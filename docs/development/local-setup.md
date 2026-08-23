# Local development setup

- **Status:** Active
- **Last verified:** 2026-08-08
- **Scope:** Windows workstation, Ubuntu 24.04 on WSL2, repository in the Linux filesystem
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)

## Supported boundary

The supported local entry point is Ubuntu on WSL2 with Docker Desktop's integration
enabled for that distribution. Keep the clone below `/home`; a clone below `/mnt/c`
or another Windows-mounted path is unsupported because mixed filesystem semantics can
change file permissions, watchers, build performance, and container bind mounts.

The prerequisite gate deliberately does not start PostgreSQL, Keycloak, the backend,
or any remote infrastructure. After it passes, use the
[backend development guide](backend.md) and
[frontend development guide](frontend.md) to start the current application
skeletons. The local PostgreSQL and Keycloak dependencies use their own explicit
lifecycle. Persistence and the opt-in OIDC BFF profile now connect to those reviewed
contracts; see the database and identity guides for application startup.

## 1. Prepare Windows and WSL2

1. Install or update WSL, set WSL 2 as the default, and install Ubuntu 24.04.
2. Install Docker Desktop and select its WSL 2 engine.
3. In Docker Desktop, open **Settings → Resources → WSL Integration**, enable the
   Ubuntu distribution, and apply the change.
4. Open a new Ubuntu terminal. Do not install a second Docker Engine inside Ubuntu;
   the supported daemon is Docker Desktop.

Docker access is intentionally through the unprivileged `docker` command. A setup
that only works with `sudo docker` does not pass this project's prerequisite gate.

## 2. Install the Ubuntu tools

Install Git, `curl`, `unzip`, an Eclipse Temurin 25 JDK (not only a JRE), and the
Node.js 24 line with its bundled npm. Follow the vendor repository setup before the
package commands when the repository is not configured yet:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git unzip
sudo apt-get install -y temurin-25-jdk
curl -fsSL https://deb.nodesource.com/setup_24.x -o /tmp/nodesource_setup.sh
sudo -E bash /tmp/nodesource_setup.sh
sudo apt-get install -y nodejs
```

Review downloaded setup scripts before running them with elevated privileges. The
Adoptium and NodeSource links in [Official references](#official-references) own the
current repository configuration instructions.

When system package installation is unavailable, official Temurin and Node.js Linux
archives may instead be unpacked below `~/.local/share`, after verifying their
published SHA-256 checksums, with `java`, `javac`, `node`, and `npm` exposed through
`~/.local/bin`. This workstation currently uses that user-scoped layout. The
repository validates the approved major lines and does not depend on a particular
version manager.

Open a new Ubuntu shell after installation so that its startup files refresh `PATH`.

## 3. Clone and configure the repository

Clone beneath the Linux home directory:

```bash
mkdir -p ~/workspace
cd ~/workspace
git clone https://github.com/rubhern/videogame-platform.git
cd videogame-platform
```

The root `.env.example` documents only Docker Compose infrastructure variables;
`backend/.env.example` documents only backend application variables. Both contain
non-secret defaults and placeholders, never usable credentials. The dependency
wrapper creates ignored `.env` files at those same locations when the topology is
first started and copies the three shared PostgreSQL/OIDC credentials between them;
never commit either file.

## 4. Run the prerequisite gate

From the repository root, run the single authoritative command:

```bash
bash scripts/validate-prerequisites.sh
```

It checks WSL2, the Linux-filesystem clone, Git, Java and `javac` 25, Node.js 24,
npm, direct Docker daemon access, Docker Compose, the executable Maven Wrapper, and
the Java runtime used by that wrapper. It reports every detected problem in one run
and exits non-zero when any mandatory prerequisite is absent or incompatible.

The CI workflow-lint wrapper additionally needs `curl`, `sha256sum`, `tar`, and a
Linux AMD64 or ARM64 host. The setup above provides them; run it independently with
`bash scripts/validate-actions.sh`. The script verifies the pinned actionlint archive
before executing it and does not install a binary globally.

Direct diagnostic commands are:

```bash
docker version
docker compose version
java --version
javac --version
node --version
npm --version
git --version
./mvnw --version
```

The Maven Wrapper is repository-owned and downloads its pinned Maven distribution on
first use. A successful `./mvnw --version` must report Java 25.

## 5. Start the backend dependencies

After the prerequisite gate passes, start PostgreSQL and Keycloak with:

```bash
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify
```

See [local backend dependencies](local-dependencies.md) for the topology, generated
credential boundary, backend connection settings, health and manifest checks, normal
shutdown, and the explicitly scoped disposable-data reset procedure.
Use the [local OIDC BFF session guide](identity-bff.md) for the OIDC-enabled combined
application and real browser compatibility command.

## 6. Opt-in complete CI parity locally

Normal development follows the risk-based, incremental policy in the
[delivery lifecycle](delivery-lifecycle.md): run the smallest meaningful local checks
for the affected boundary and rely on trusted GitHub CI for complete repository
integration validation on `main`. When a cross-cutting/high-risk change, insufficient
CI evidence, critical release, local-only failure, or explicit owner request justifies full local parity,
use the exact opt-in sequence in the
[walking-skeleton continuous-integration guide](continuous-integration.md).

## Troubleshooting

| Symptom | Supported action |
|---|---|
| `docker: command not found` | Start Docker Desktop, confirm the WSL 2 engine, enable the Ubuntu integration, then restart the WSL shell. |
| Docker client exists but cannot reach the server | Start Docker Desktop and re-check **Settings → Resources → WSL Integration**. Remove a stale custom `DOCKER_HOST` unless it is intentional. |
| Docker works only with `sudo` | Do not add a parallel Ubuntu daemon as a workaround. Re-enable Docker Desktop integration and verify `docker context show` uses the intended context. |
| Java or `javac` is not version 25 | Select the Temurin 25 JDK in `PATH` and, if used, set `JAVA_HOME` to that JDK. A JRE alone is insufficient. |
| Node is not version 24 or npm is absent | Replace the active Node installation with the Node.js 24 line and open a fresh shell. Avoid mixing Windows and WSL Node installations. |
| `mvnw` is not executable | Restore the tracked executable bit with `git update-index --chmod=+x mvnw` and re-check the working tree. |
| Maven cannot download on first use | Check DNS, HTTPS proxy, and access to Maven Central. Do not commit proxy credentials. |
| Repository-location check fails | Re-clone beneath `/home`; do not move build output between `/mnt/c` and the Linux clone. |

## Explicitly out of scope

- starting the backend application or frontend development processes;
- provisioning OCI or selecting a paid fallback;
- installing optional IDE integrations;
- supporting a second Docker daemon inside Ubuntu;
- claiming macOS, native Windows, or another Linux distribution as a proven local
  baseline.

## Official references

- [Docker Desktop WSL2 backend](https://docs.docker.com/desktop/features/wsl/)
- [Docker development with WSL2](https://docs.docker.com/desktop/features/wsl/use-wsl/)
- [Eclipse Temurin installation](https://adoptium.net/installation/)
- [NodeSource Node.js distributions](https://github.com/nodesource/distributions)
