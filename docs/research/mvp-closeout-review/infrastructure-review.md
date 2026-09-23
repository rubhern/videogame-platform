# MVP close-out infrastructure review

- **Type:** Point-in-time infrastructure review (evidence, not an approved decision)
- **Reviewer:** AI-assisted review pass; the owner retains approval authority
- **Revision reviewed:** `d10bc44` on `main`, clean working tree
- **Scope:** the owner-managed private `dev` host (`vgpdev`), Docker Engine/Compose
  runtime, networking and exposure, Tailscale administration and private HTTPS,
  PostgreSQL, Keycloak, persistent storage, configuration and secret handling,
  privileges and isolation, reboot and host-loss recovery, backup/restore, capacity,
  maintenance, portability and reproducibility.
- **Out of scope:** CI/CD pipelines, branching, quality gates, application testing,
  GitHub Actions deployment strategy, functional observability, application
  instrumentation, code and architecture. Items that belong there are tagged
  `OUT_OF_SCOPE` and not developed. The [code review](code-review.md) and the
  [architecture review](architecture-review.md) cover the other two.
- **Sources contrasted:** ADR-0019, ADR-0002/0003/0007/0011,
  `docs/architecture/deployment/mvp-platform-and-delivery.md`, the technology
  baseline, the solution architecture, the delivery lifecycle,
  `deploy/private-dev/*` (Compose, README, `bin/*`, `runtime.env.example`, Keycloak
  and smoke Dockerfiles, collector config), the root `Dockerfile` and `.dockerignore`,
  `compose.yaml`, `.env.example`, `backend/.env.example`,
  `docker/postgres/init/001-create-databases.sh`, the shared realm import,
  `scripts/validate-private-dev-runtime.sh`, `application.yaml`, and the owner
  evidence recorded in issues #124, #43, #36 and #44.
- **Method:** repository configuration was read in full; host facts come only from
  the evidence the owner recorded in the issues. Where a host setting cannot be seen
  from the repository (firewall, `sshd_config`, Tailscale key expiry, unattended
  upgrades, realm state after import), the finding says so and asks for
  confirmation rather than assuming.

Nothing here changes code, infrastructure, configuration, ADRs or canonical
documentation.

---

## 1. Executive summary

The private `dev` infrastructure is simple, well isolated and close to the approved
design. One laptop, Ubuntu Server 24.04, Docker Engine and Compose, owner-only
Tailscale administration, Tailscale Serve as the only HTTPS edge, no router
forwarding, no public IPv6, digest-pinned images, loopback-only publication,
internal-only data and telemetry networks, file-based secrets outside Git with
per-service grants, least-privilege PostgreSQL roles, and hardened non-root
containers for everything except the PostgreSQL image. The owner's #43 evidence
confirms the dependency stack runs on the host, survives container restarts and a
full reboot, publishes no protected port, and leaks no secret into container
metadata or logs. For a zero-cost, single-owner, private MVP this is more than
adequate; nothing here argues for another host, a cloud, an orchestrator or a
secret manager.

What is missing is not infrastructure but **operational closure** around three
things:

1. **Backup and restore do not exist yet** (`IR-01`, HIGH). #44 is open and correctly
   scoped, but the MVP should not close without it: the Keycloak database (which
   anchors every product `UserId` through `issuer + subject`), the secrets directory
   and, from the first deployment onward, every personal rating exist only on one
   consumer SATA SSD in a laptop. The repository already contains everything a
   minimal, tested, encrypted, off-host backup needs.
2. **Reproducibility has two soft spots** (`IR-02`, `IR-04`). The running stack
   bind-mounts entrypoint wrappers, the realm import, the database bootstrap and the
   collector configuration straight from the Git checkout, and the deployment
   evidence records the image digest but not the checkout revision that supplied
   those files. The Keycloak realm is imported once and then drifts from Git by
   design, with no written procedure for later changes, a permanent admin, or
   rotation.
3. **The host runbook is spread across a design document, a README and issue
   comments, and stops before day-two operations** (`IR-03`, `IR-05`, `IR-06`): host
   firewall and SSH interface policy, Tailscale key expiry, OS and Docker update
   cadence, pinned-image refresh, disk pruning, SMART/thermal checks, and the way the
   operator actually triggers catalogue synchronization on `dev` (the management port
   is unpublished by design and the backend README shows the local invocation only).

Findings: **1 HIGH, 5 MEDIUM, 4 LOW**, plus six recorded revisit triggers. Three
GitHub issues are suggested; one of them is a refinement of the existing #44.

Environment state, to avoid reading design as deployment:

| State | What |
|---|---|
| **Existing on `vgpdev`** (per #124/#43 evidence) | Ubuntu Server 24.04 `x86_64`, Kingston SA400 960 GB SSD (891 GiB root LV, LVM), 7.6 GiB RAM, 4 GiB swap, Ethernet with DHCP reservation, key-based SSH, Tailscale on host and Windows, lid/suspend disabled, Docker Engine and Compose; PostgreSQL, Keycloak and the OpenTelemetry Collector running from `deploy/private-dev/compose.yaml`; Tailscale Serve HTTPS on 443 and 8443; protected `/etc/videogame-platform/dev` with `runtime.env` and secret files |
| **Approved, pending** | Application deployment through `deploy-private-dev` (#36 open; the recent `#143`–`#146` commits are the smoke fixes for its first run), backup/restore/host-loss recovery (#44 open), complete journey validation (#45 open) |
| **Deferred by decision** | Public ingress, HA, staging, Kubernetes, distributed components, paid services, durable telemetry backend, automatic synchronization, hardware upgrade (ADR-0019: only on measured pressure) |

---

## 2. Current infrastructure assessment

**Host.** One Lenovo Y520 running Ubuntu Server 24.04 LTS headless, with the
reconstruction steps owned by the platform design (six steps: install, LVM, network
and Tailscale, lid/suspend, Docker, baseline measurements). Owner evidence: SMART
`PASSED`, idle 563 MiB used, 35–38 °C, reboot recovery of SSH/Tailscale/Docker
verified, firmware boot order corrected. What the repository cannot show: `sshd_config`
beyond "disable password/root login", any host firewall, unattended-upgrade status,
Tailscale key expiry, power-restoration behaviour (recorded as untested by design).

**Runtime topology** (`deploy/private-dev/compose.yaml`). Four long-running services
(`postgres`, `keycloak`, `telemetry`, profile-gated `application`) and two one-shot
actors (`migration`, `deployment-smoke`). Three networks: `edge` (bridge, only for
loopback publication and outbound), `data` (internal), `telemetry` (internal).
Published ports: `127.0.0.1:8080→application:8080` and `127.0.0.1:8180→keycloak:8080`
only. PostgreSQL (5432), OTLP (4318), the application management port (8081) and
Keycloak management (9000) have no host listener, which
`validate-private-dev-runtime.sh --live` asserts for both IP families. Tailscale
Serve terminates HTTPS on the tailnet name at 443 (application) and 8443
(Keycloak, including its admin console).

**Containers.** Every image is digest-pinned. Keycloak and the smoke runner are
built on the host from digest-pinned bases; the application is pulled by digest from
GHCR and verified against OCI labels before activation. `keycloak`, `telemetry`,
`application`, `migration` and `deployment-smoke` run with `read_only`, `tmpfs`,
`cap_drop: ALL`, `no-new-privileges`, `pids_limit`, `mem_limit`, `cpus`, bounded
`local` logging and `init` where relevant; the application image itself is non-root
(`10001`). `postgres` uses the official image's root entrypoint with `gosu` and has
resource limits and bounded logging but none of the capability or privilege
restrictions (`IR-07`). Restart policy is `unless-stopped` for the long-running
services and `no` for the actors; the owner verified restart and reboot persistence
for the dependency stack.

**Secrets and configuration.** Real secrets are ten files under
`/etc/videogame-platform/dev/secrets` (0750 `root:vgp-runtime` directory, 0644
files), created by `prepare-secrets` with `openssl rand`, granted per service through
Compose `secrets:` bind mounts and a `group_add`. Entrypoint wrappers read the files
and `exec` the process; Compose metadata never carries a value, and the live
validator greps container `Config.Env` for forbidden names. `runtime.env` holds only
host-specific non-secret values. Nothing secret is in Git, images or `.env.example`
files. Rotation is documented as a two-step manual operation without a procedure.

**Data.** One PostgreSQL 18 server with two databases and three least-privilege
roles created by the shared bootstrap script; the application runtime role has no
DDL; migrations run once as the migrator role from the selected image before
application replacement. Persistent state is one named Docker volume
(`postgres-data`) on the root LV. There is no backup, no restore procedure and no
clean-room reconstruction test yet (#44).

**Identity.** Keycloak 26.7 with `KC_CACHE: local`, its own database and role,
`KC_HOSTNAME` set to the private HTTPS origin, `xforwarded` proxy headers, health and
metrics on the container-internal management port, the shared realm imported once
with the client secret and public origin substituted from files. The private-dev
stack deliberately does not mount the synthetic local user; the deployment-smoke
account is provisioned idempotently through the Admin API. Self-registration is
enabled in the realm because the product brief requires delegated registration; on a
tailnet limited to the owner this is acceptable.

---

## 3. Current infrastructure strengths

- **Exposure is minimal and verified.** No router forwarding, no UPnP, no global IPv6
  address, no public listener; Funnel prohibited and checked; loopback-only
  publication asserted per IP family; internal networks for data and telemetry;
  Tailscale Serve as the single private edge. The #43 evidence records an external
  check with Tailscale disconnected.
- **Least privilege is real.** Separate PostgreSQL roles for runtime, migration and
  Keycloak; the runtime role cannot create or alter schema; migrations happen in a
  one-shot actor on the data network only; Keycloak and the application never see
  each other's database credentials; the smoke account has no role or group.
- **Secrets stay out of every wrong place.** Not in Git, images, Compose metadata,
  command arguments, URLs, evidence records or container logs; the validator checks
  the metadata claim on the live stack; optional IGDB credentials default to empty
  so synchronization is disabled unless the owner opts in.
- **Images are immutable and verified.** Digest pins everywhere, OCI label checks
  before activation, the candidate container's image ID compared with the inspected
  image, and `latest` rejected by the deployment script.
- **Resource and log bounds exist for every service**, so one runaway container
  cannot take the host down or fill the disk with logs.
- **Deployment is deliberately manual, locked and evidenced.** Host name check, one
  non-blocking `flock`, migration before replacement, readiness and smoke bound to
  the new container, a JSON evidence record even on failure.
- **The design document is honest about what is and is not proven** and keeps
  addressing, tailnet names and credentials out of Git.

---

## 4. Findings

Priority meaning: `HIGH` = clear data-loss, secret-exposure, unauthorised-access,
recoverability or isolation risk; `MEDIUM` = a real operational or reproducibility
gap; `LOW` = worth doing opportunistically. Type: `CURRENT_PROBLEM` exists today;
`IMPROVEMENT` is a better fit for the current environment; `REVISIT_TRIGGER` is
not current work.

### HIGH

#### `IR-01` — No backup, restore, or reconstruction test exists for irreplaceable state

- **Priority:** HIGH · **Type:** CURRENT_PROBLEM (approved, pending as #44) · **Area:** recovery / storage / identity
- **Evidence:** #44 open with all acceptance boxes unchecked; platform design
  ("Back up irreplaceable ratings, identity mapping/configuration, and product
  editorial outside the host, encrypted and within the zero-cost constraint … prove
  isolated restore"); `compose.yaml` (`postgres-data` named volume, single host);
  #124 closed with "The host foundation can be reconstructed from concise
  documented steps" unchecked; `identity/domain/UserId.java` (identity derived from
  Keycloak `issuer + subject`).
- **Problem observed:** every durable byte of the environment lives on one consumer
  SATA SSD inside a laptop: the Keycloak database (users, their `sub` values,
  credentials, realm state including the imported client secret), the secrets
  directory and `runtime.env`, and, from the first #36 deployment onward, the
  ratings. There is no copy off the host, no restore procedure, no retention policy,
  and the six reconstruction steps have never been executed on a clean machine. The
  Keycloak database is more important than it looks: recreating the owner's user
  after a loss produces a new `sub`, so every rating would be orphaned even if the
  application database were recovered (see architecture review `AR-07`).
- **Real risk:** total, silent loss of personal and identity state on SSD failure,
  filesystem corruption, an accidental `docker compose down --volumes`, or a mistaken
  reinstall. A Kingston A400 is a DRAM-less consumer drive; SMART `PASSED` today is
  not a durability guarantee.
- **Proposal (the simplest design that meets #44):**
  1. A host-side script run by a `systemd` timer (daily, off-hours) that executes
     inside the running container `pg_dump --format=custom` for `videogame_platform`
     and `videogame_keycloak` plus `pg_dumpall --globals-only`, adds a tarball of
     `/etc/videogame-platform/dev` (runtime.env and secrets, because a restore
     without them means rotating every credential and re-importing the realm), and a
     small manifest (UTC time, PostgreSQL version, current Flyway version from
     `flyway_schema_history`, application image digest from the last evidence
     record).
  2. Encrypt with `age` to a public key whose private key lives only on the owner's
     workstation, never on the host.
  3. Copy off-host at zero cost: `tailscale file cp` or `scp` to the Windows machine
     over the tailnet, or `rclone` to an existing free personal drive if the owner
     already has one. Keep seven daily and four weekly copies; prune on the host and
     at the destination.
  4. Prove restore into a disposable Compose project on the same host (different
     `COMPOSE_PROJECT_NAME`, different secrets directory), then verify: both
     databases restore, the realm and the owner's user exist with the same `sub`,
     the application reads the catalogue, and a login round-trip succeeds. Repeat
     after any material change and record the run in #44.
  5. Execute the six host reconstruction steps once on a throwaway VM (or the host
     itself before the first real data exists) and correct the document where it
     is wrong; this is the only way "can be reconstructed" becomes true.
- **Simpler alternative considered:** rely on the SSD and re-sync the catalogue.
  Rejected: identity and ratings are not resyncable and the design already says so.
- **Trade-offs:** one more host-side script and timer to maintain; a private key to
  keep safe; ~10 minutes of owner time per restore drill.
- **Cost:** medium · **Issue:** yes (refine #44 with this scope) · **ADR:** no

### MEDIUM

#### `IR-02` — The running stack depends on the Git checkout at runtime, and the deployment evidence does not record which checkout revision supplied it

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** containers / recovery / other (reproducibility)
- **Evidence:** `deploy/private-dev/compose.yaml` bind-mounts `./bin/run-application`,
  `./bin/run-keycloak`, `./bin/run-migrations`, `../../docker/postgres/init`,
  `../../docker/keycloak/import/videogame-platform-realm.json` and
  `./otel/collector.yaml` from the checkout; `deploy-private-dev` records
  `sourceRevision` (the image's) and `image` but no checkout revision or dirty flag;
  the README's host steps run `docker compose … --file deploy/private-dev/compose.yaml`
  from a checkout whose path and branch are not recorded anywhere.
- **Problem observed:** a container restart (including after reboot, through the
  restart policy) re-reads the wrapper scripts and collector configuration from
  whatever the checkout contains at that moment. Switching branches on the host,
  pulling `main` between deployments, or deleting the checkout changes or breaks the
  running stack without any deployment having happened, and nothing records which
  version of the Compose file, wrappers, bootstrap or realm was in force when a
  given evidence record was written.
- **Real risk:** silent runtime drift and an unreconstructible "what exactly was
  running" after an incident; a missing checkout after a reboot prevents the
  application from starting at all.
- **Proposal:**
  1. In `deploy-private-dev`, record `git -C "$repository_root" rev-parse HEAD` and
     the `git status --porcelain` dirty state in the evidence JSON, and refuse to
     deploy from a dirty checkout or from a revision that is not the supplied
     `--source-revision` (the image and the runtime files then come from the same
     `main` commit, which is what the design intends).
  2. Replace the application wrapper with Spring Boot's native
     `spring.config.import=optional:configtree:/run/secrets/` (the property names
     already match the file names once the files are renamed to
     `APPLICATION_DB_PASSWORD` etc., or the environment variables are mapped in
     `application.yaml`), which removes one bind-mounted script and keeps secrets
     out of the process environment (`IR-08`). The PostgreSQL image already reads
     `*_FILE` natively. Keycloak still needs its wrapper.
  3. Document in the README that the checkout path is part of the runtime and must
     stay on the deployed `main` revision.
- **Simpler alternative considered:** copy the runtime files into
  `/etc/videogame-platform/dev/runtime/` at deployment and mount from there.
  Workable, but it duplicates files the deploy script then has to keep in sync;
  recording and pinning the checkout is cheaper and sufficient for one operator.
- **Trade-offs:** the deploy script gains a Git dependency on the host (already
  present as a checkout); `configtree` changes how the application receives three
  secrets and must be validated locally first.
- **Cost:** small · **Issue:** yes · **ADR:** no

#### `IR-03` — Host ingress policy is not defined beyond "no forwarding": no firewall, SSH interface binding, or Tailscale key-expiry decision is recorded

- **Priority:** MEDIUM · **Type:** IMPROVEMENT (hypothesis to confirm on the host) · **Area:** network / host
- **Evidence:** platform design step 3 (SSH key, disable password/root login, check
  router forwarding) and the #43 evidence (no forwarding, no UPnP, no global IPv6,
  external check with Tailscale disconnected); no mention anywhere of `ufw`/nftables,
  of the interfaces `sshd` listens on, or of Tailscale node key expiry.
- **Problem observed:** by default Ubuntu Server ships without an active firewall,
  `sshd` listens on all interfaces, and Tailscale node keys expire after 180 days
  unless expiry is disabled for the node. As far as the repository can tell, `vgpdev`
  therefore accepts SSH from every device on the home LAN (guests, IoT devices,
  anything that joins the Wi-Fi), and will silently drop off the tailnet on the day
  its key expires, after which remote administration requires physical or LAN
  access.
- **Real risk:** LAN-side SSH exposure is bounded by key-only authentication but is
  larger than the design intends ("administrative access is unavailable from
  outside the tailnet/LAN" treats the LAN as trusted, which a home network with
  consumer devices is not); key expiry is an operational lockout waiting to happen.
- **Proposal:** confirm on the host and then record in the platform design step 3:
  (1) disable key expiry for `vgpdev` in the Tailscale admin console (or document
  the re-authentication reminder); (2) `ufw default deny incoming`, `allow in on
  tailscale0`, and either no LAN SSH at all or `allow from <LAN subnet> to any port 22`
  as break-glass if the owner wants console-free recovery when Tailscale is down;
  Docker's own forwarding chains are unaffected because the published ports are
  loopback-only; (3) `sshd` `PasswordAuthentication no`, `PermitRootLogin no`,
  `KbdInteractiveAuthentication no` written down, not just intended.
- **Simpler alternative considered:** Tailscale SSH instead of OpenSSH. Rejected for
  now: it changes the administration path the owner has already verified and adds a
  dependency on Tailscale for the break-glass path.
- **Trade-offs:** a firewall misconfiguration can lock the owner out; apply it from
  a console session or with a LAN break-glass rule first.
- **Cost:** small · **Issue:** yes (host runbook) · **ADR:** no

#### `IR-04` — Keycloak realm state drifts from Git after the first import, and the bootstrap admin, the owner's user and rotation have no written procedure

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM (documentation and reproducibility gap) · **Area:** identity / recovery
- **Evidence:** `run-keycloak` (`kc.sh start --optimized --import-realm`, which
  skips an existing realm); `compose.yaml` (`KC_BOOTSTRAP_ADMIN_*` from files);
  platform design ("Changing a file does not rotate an already-created PostgreSQL
  role or imported Keycloak client: rotation must update the owning service state
  and then replace the file as one reviewed operation"); `provision-oidc-smoke-user`
  (idempotent Admin API pattern, used for one account only); no procedure for
  creating the owner's product user on `dev` (the local synthetic user is
  deliberately not mounted).
- **Problem observed:** the realm JSON in Git is the *initial* state only. After the
  first start, every later change (redirect URIs if the origin changes, session
  lifetimes, the client secret, registration or brute-force settings) must be made
  through the admin console or the Admin API and is not recorded anywhere; the
  `KC_BOOTSTRAP_ADMIN` account is Keycloak's temporary bootstrap admin, which the
  product docs recommend replacing with a permanent one; and the owner's real user
  (the one whose `sub` anchors their ratings) is created by hand with no note that
  it must never be deleted and recreated.
- **Real risk:** a second `dev` host or a restore reproduces the Git realm, not the
  live one; an origin change or secret rotation done half-way leaves login broken;
  a well-meant "recreate my user" destroys the link to all personal data.
- **Proposal:** a short "Keycloak on dev" section in `deploy/private-dev/README.md`
  that states: the import is bootstrap-only; how to apply a realm change (admin
  console, then mirror the change in the JSON for the next fresh environment); how
  to rotate each of the three Keycloak-related secrets in order; that a permanent
  admin should be created once and the bootstrap admin removed; and that product
  users must never be deleted and recreated because `sub` is identity. Optionally
  extend the existing provisioning script to reconcile the two origin-derived client
  fields idempotently, since it already talks to the Admin API safely.
- **Simpler alternative considered:** re-import the realm with override on every
  start. Rejected: it would reset user state and is exactly the wrong direction for
  a database that holds identity.
- **Trade-offs:** documentation only; the optional reconcile step adds a few lines to
  an existing script.
- **Cost:** small · **Issue:** yes (host runbook) · **ADR:** no

#### `IR-05` — Catalogue synchronization has no documented invocation path on `dev`, and enabling its credentials requires a restart nobody mentions

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM (operability) · **Area:** other (operations) / secrets
- **Evidence:** `compose.yaml` (management port 8081 unpublished; `run-application`
  reads `igdb-client-id`/`igdb-client-secret` once at start); `backend/README.md:172-176`
  (`curl … http://localhost:8081/actuator/cataloguesync`, valid for the local run
  only); `deploy/private-dev/README.md` ("Populate IGDB credentials only for an
  explicitly initiated catalogue sync"; no command); platform design ("the backend
  guide owns invocation"); ADR-0017 (one POST holds the whole interval).
- **Problem observed:** on `dev` the only route to the management port is from
  inside the container or another container on its networks, so the documented
  `curl` cannot work; the supported form is
  `docker compose … exec application wget --post-data … http://127.0.0.1:8081/actuator/cataloguesync`
  (BusyBox `wget` is present because the health check uses it). Filling the two
  IGDB files does nothing until the application container is recreated. A long
  interval holds the `exec` session for minutes and the operator has no guidance on
  detaching, on `lastRun`, or on the 30-minute abandon fence.
- **Real risk:** the first attempt to populate the `dev` catalogue fails or is
  improvised, or credentials are put somewhere convenient instead of the protected
  files.
- **Proposal:** add the `dev` invocation to the backend README's synchronization
  section (or a pointer from the private-dev README): populate the two files, restart
  only the application service, run the POST through `docker compose exec` with
  `--timeout` and `nohup`/`tmux` guidance for long intervals, read `lastRun`
  afterwards, and empty the files again if synchronization should stay disabled
  between runs. Keep the management port unpublished; do not add a host route for it.
- **Simpler alternative considered:** publish 8081 on loopback like 8080. Rejected:
  it widens the unauthenticated management surface to every local process for a
  command the owner runs a few times a month.
- **Trade-offs:** none beyond documentation.
- **Cost:** small · **Issue:** yes (host runbook) · **ADR:** no

#### `IR-06` — Day-two host maintenance is undefined: OS and Docker updates, pinned-image refresh, image pruning, SMART/thermal checks, battery

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** host / capacity
- **Evidence:** platform design steps 5–6 (install Docker, install `smartmontools`
  and `lm-sensors`, record a baseline); no cadence for `apt` security updates or
  reboots, no statement about `unattended-upgrades`, no process for refreshing the
  digest-pinned PostgreSQL/Keycloak/collector/Playwright images, no `docker image
  prune` guidance although every deployment pulls a new application digest and
  rebuilds the ~2 GB Playwright smoke image with `--pull`; the laptop runs closed-lid
  on mains with its battery permanently charged.
- **Problem observed:** the host will accumulate kernel updates that need a reboot,
  Docker Engine updates that restart every container, old application images and
  dangling smoke layers, and it has no scheduled SMART or temperature observation
  after the one-off baseline. None of this is broken today; all of it is how a
  well-set-up single host degrades over a year.
- **Real risk:** unpatched kernel/Docker for long periods; an unexpected container
  restart during an `apt upgrade`; a full disk only in the very long run (891 GiB
  makes this a distant risk); undetected SSD wear or thermal throttling during a
  sustained synchronization run; battery swelling on a laptop kept at 100 % for
  years.
- **Proposal:** one "monthly host routine" section in the platform design or the
  private-dev README: confirm `unattended-upgrades` handles security updates and
  decide whether reboots are manual (recommended: manual, after a backup) or
  automatic at a fixed hour; `apt` update Docker Engine deliberately, expecting a
  container restart; `docker image prune` keeping the current and previous
  application digest; `smartctl -H` and `sensors` recorded in #124's successor or a
  private log; enable a battery charge threshold if the firmware or
  `/sys/class/power_supply` supports it; refresh pinned dependency images through
  the normal PR path (`OUT_OF_SCOPE`: the CI/Dependabot mechanics of that refresh).
- **Simpler alternative considered:** do nothing until something breaks. Rejected
  only because the cost of writing the routine is an hour and each item has a known
  failure mode.
- **Trade-offs:** a small recurring owner chore.
- **Cost:** small · **Issue:** yes (host runbook) · **ADR:** no

### LOW

#### `IR-07` — The PostgreSQL container lacks the privilege restrictions every other service has

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** containers
- **Evidence:** `compose.yaml` `postgres` service: no `cap_drop`, no
  `security_opt: no-new-privileges`, not `read_only`; all five other services have
  them. The official image starts as root, `chown`s the data directory and drops to
  `postgres` through `gosu`.
- **Problem observed:** inconsistency rather than exposure: PostgreSQL is on the
  internal `data` network only, unpublished, with resource limits. A container
  escape from PostgreSQL is outside the approved threat model.
- **Proposal:** `cap_drop: ALL` plus `cap_add: [CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID]`
  and `security_opt: [no-new-privileges:true]`, which the official image tolerates
  (`gosu` does not rely on setuid binaries). Leave the filesystem writable; the
  data directory and `/run/postgresql` need it.
- **Cost:** small · **Issue:** yes (grouped with `IR-02` Compose changes) · **ADR:** no

#### `IR-08` — Secrets are exported into container process environments by the wrappers

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** secrets
- **Evidence:** `run-application`, `run-migrations`, `run-keycloak`,
  `001-create-databases.sh` (`export VAR=$(cat file)` then `exec`); platform design
  ("without putting values in Compose environment metadata or command arguments",
  which is satisfied).
- **Problem observed:** the values are absent from `docker inspect` and logs but
  present in `/proc/<pid>/environ` of the main process and visible to
  `docker compose exec application env`. Spring Boot does not expose `env` through
  Actuator here (only `health,info,metrics,cataloguesync` are exposed), so nothing
  serves them over HTTP. This is within the single-owner threat model.
- **Proposal:** for the application and the migration actor, `configtree:` import
  (see `IR-02`) removes the environment step entirely; for Keycloak and the
  PostgreSQL bootstrap the wrappers are the supported mechanism and can stay.
- **Cost:** small · **Issue:** yes (grouped with `IR-02`) · **ADR:** no

#### `IR-09` — Destructive Compose commands on `dev` have no guard

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** storage / recovery
- **Evidence:** `deploy/private-dev/README.md` (raw `docker compose … up/pull/build`
  commands); local development has a wrapper (`scripts/local-dependencies.sh`) that
  refuses a reset against another project name; `dev` has no equivalent.
- **Problem observed:** `docker compose --file deploy/private-dev/compose.yaml down --volumes`
  deletes `postgres-data` and with it Keycloak identity and ratings. Nothing prevents
  the muscle-memory version of a local reset from running on the host.
- **Proposal:** one sentence in the README ("never pass `--volumes`, `-v` or run
  `docker volume rm`/`system prune --volumes` on `vgpdev`") and, once `IR-01` exists,
  the backup makes the mistake recoverable. A wrapper script is not worth it for one
  operator.
- **Cost:** small · **Issue:** no (fold into the runbook) · **ADR:** no

#### `IR-10` — Two container-internal tunables are unmeasured hypotheses: JVM heap share and PostgreSQL `shared_buffers`

- **Priority:** LOW · **Type:** IMPROVEMENT (measure first) · **Area:** capacity
- **Evidence:** `Dockerfile` (`ENTRYPOINT java -jar` with no `-Xmx` or
  `MaxRAMPercentage`); `compose.yaml` (`mem_limit: 1536m` for the application,
  `768m` for PostgreSQL); no `postgresql.conf` override (defaults: 128 MB
  `shared_buffers`, 100 connections).
- **Problem observed:** the JVM's default in a container is 25 % of the limit
  (about 384 MiB heap); Keycloak by contrast sizes itself at 70 %. Whether 384 MiB is
  enough for the application plus a synchronization run is unknown. PostgreSQL's
  128 MB buffer cache is fine for the current data and may be small for a
  100 000-game catalogue's GIN and GiST indexes.
- **Proposal:** measure before changing: `docker stats` and Actuator `jvm.memory.used`
  / `jvm.memory.max` during the first real synchronization and during the smoke;
  `pg_stat_database` and `pg_statio_user_indexes` hit ratios after the catalogue
  has real size. If heap pressure shows, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50`
  in the Compose environment is the whole change; if PostgreSQL cache misses show,
  `shared_buffers=256MB` through `command: postgres -c shared_buffers=256MB` is.
- **Cost:** small · **Issue:** no (record in #36/#45 evidence) · **ADR:** no

---

## 5. Security and exposure assessment

Attack surfaces, from the outside in, at the reviewed configuration:

| Reachable from | Listener | Control | Assessment |
|---|---|---|---|
| Internet | none | No router forwarding or UPnP (owner-verified), no global IPv6, Funnel prohibited and checked; Tailscale itself is outbound-only WireGuard | Closed. Re-verify after any router change or firmware update |
| Tailnet (owner devices only by policy) | 443 application, 8443 Keycloak incl. `/admin`, 22 SSH via the Tailscale address | Tailscale identity plus Keycloak/product authorization; admin console behind the bootstrap admin credential; SSH key-only | Adequate for one owner. The admin console being tailnet-wide is fine while the tailnet has one user; it is the first thing to restrict if a second person ever joins |
| Home LAN | 22 SSH (all interfaces, presumed), Tailscale UDP 41641, whatever else Ubuntu enables | Key-only SSH; no firewall recorded | `IR-03`: the LAN is treated as trusted; recommend a default-deny firewall with a tailnet allow and an explicit LAN break-glass decision |
| Host loopback | 8080 application, 8180 Keycloak (plain HTTP) | Only local processes and Tailscale Serve reach loopback; forwarded headers are trusted from loopback, which is correct here | Adequate |
| Docker `edge` network | application 8080/8081, Keycloak 8080/9000, smoke | Unauthenticated Actuator including the `cataloguesync` write operation is reachable from Keycloak and the smoke container | Within the threat model (all images pinned, all ours); noted, not recommended for change because the smoke needs `/actuator/info` and metrics |
| Docker `data` / `telemetry` networks | PostgreSQL 5432, OTLP 4318 | Internal networks, SCRAM auth, per-service roles | Adequate |

Trust assumptions worth stating: (1) Tailscale's policy is the only thing keeping
non-owner devices away from the application, Keycloak and its admin console; the
design says Tailscale is not a substitute for Keycloak, and it is not, but it *is*
the substitute for a WAF, rate limiting and admin-console IP restriction, which is
acceptable at one user. (2) The operator account is either in the `docker` group
(the private-dev README runs `docker compose` without `sudo`) or uses `sudo` for
every Docker command (the platform design's step 5 does); the two documents differ,
and either way the operator is root-equivalent on the host. Acceptable for a single
owner; the runbook (`IR-03`) should state which one is in force, and rootless Docker
is not recommended (section 9). (3) Self-registration is enabled in the realm because the product
requires delegated registration; brute-force detection and a password policy are
not configured. On an owner-only tailnet this is not a current risk; both become
mandatory before any change of release mode (`RT-4`).

Secret handling is sound: generation, placement, permissions, per-service grants and
validation are all in place; the residual items are process-environment exposure
(`IR-08`, low) and the absence of a written rotation procedure (`IR-04`). No secret,
tailnet identifier or address was found in Git, images, `.env.example` files, the
evidence schema or the smoke logs.

---

## 6. Persistence and recoverability assessment

**Classification of state on `vgpdev`:**

| State | Where | Class | Recovery today |
|---|---|---|---|
| Keycloak database: realm as imported and later edited, users, `sub` values, credentials, smoke account | `postgres-data` volume, `videogame_keycloak` | **Irreplaceable** (anchors `UserId`) | None |
| Personal ratings (`ratings.rating`), listing projection | `postgres-data`, `videogame_platform` | **Irreplaceable** (projection is rebuildable) | None; not yet present until #36 |
| Any human curation in the catalogue: `verified` evidence, review decisions, `product_curated` aliases, editorial summaries | `videogame_platform` | **Irreplaceable** when it exists; none expected on `dev` today (the dev seed is excluded from the packaged migrations) | None |
| Provider-derived catalogue and synchronization history | `videogame_platform` | Resyncable from IGDB at provider-rate cost (an hour or more per large interval) | Re-run synchronization |
| `runtime.env`, ten secret files | `/etc/videogame-platform/dev` | **Irreplaceable for a clean restore** (without them every credential rotates and the realm client secret no longer matches the restored realm) | None |
| Deployment evidence records | `/var/lib/videogame-platform/dev/deployment-evidence` | Valuable, not critical | None |
| OS, Docker, Tailscale enrollment, lid/suspend, boot order, DHCP reservation, Serve routes | host | Rebuildable from the six steps plus the README; never exercised clean-room | Manual |
| Images | GHCR (application), quay/Docker Hub/MCR (pinned bases), host-built Keycloak and smoke images | Rebuildable by digest | Pull/build |

**Persistence mechanics.** The single named volume sits on the root LV; container
lifecycle does not remove it (`down` without `--volumes` keeps it, `IR-09`). Restart
policies bring the dependency stack back after a reboot without owner action
(verified in #43). The application container restarts too, but Docker restarts
containers without honouring `depends_on`; the application fails fast if PostgreSQL
is not yet accepting connections and is restarted with back-off until it is, which is
acceptable but should be observed once during the #36 evidence (a few restart lines
in the log are expected, a crash loop is not). Keycloak is not needed for the
application to start because the OIDC endpoints are configured explicitly.

**Power loss.** ADR-0019 accepts that availability has no guarantee. The laptop
battery is an incidental UPS for short outages; whether the firmware restarts on
power restoration is recorded as untested. Someone must press the power button after
a long outage; that is a known consequence, not a finding.

**Recovery objectives.** None are defined. For a private single-owner MVP a daily
backup (RPO ≤ 24 h) and a same-day restore (RTO "one evening") are reasonable and
are what `IR-01` provides. Point-in-time recovery (WAL archiving) is not justified
until a measured need exists (`RT-2`).

---

## 7. Capacity assessment

**Current evidence** (owner-recorded, #124 and #43): 7.6 GiB RAM, 4 GiB swap unused,
891 GiB root LV on a 960 GB SATA SSD, idle host ≈ 563 MiB used; with PostgreSQL,
Keycloak and the collector running under idle and representative activity ≈ 1.2 GiB
used and 6.4 GiB available, low CPU/load, 35–38 °C at idle, no swap use, ample disk.

**Configured ceilings** (`compose.yaml`): steady state application 1.5 GiB + Keycloak
1.5 GiB + PostgreSQL 0.75 GiB + collector 0.25 GiB = 4.25 GiB of memory limits and
4.25 CPUs; during a deployment the migration actor (0.75 GiB, before replacement)
and the smoke runner (0.75 GiB plus 256 MiB `shm`) run one after the other, so the
transient peak is about 5.25 GiB of limits against 7.6 GiB physical. Limits are
ceilings, not reservations; real use of the dependency stack was 1.2 GiB.

**Assessment by workload:**

| Workload | Evidence / reasoning | Class |
|---|---|---|
| Application at rest and under one owner's browsing | JVM with 384 MiB default heap ceiling, Tomcat, Hikari pool of 10; comparable Spring Boot services idle at 300–600 MiB RSS | Reasonable risk: fits; measure heap once (`IR-10`) |
| PostgreSQL for the MVP catalogue and personal data | ADR-0015/0016 evidence handled 100 000 games / 500 000 releases with millisecond queries on a workstation; data of that size is a few GiB on disk and well inside the 891 GiB LV; default 128 MB buffer cache may thrash on a large catalogue | Hypothesis requiring measurement before tuning (`IR-10`) |
| Keycloak for one user | 1.05 GiB heap ceiling at 70 %; #43 measured the whole stack at 1.2 GiB used | Current evidence: fits |
| Collector | 192 MiB memory limiter, debug exporter only | Current evidence: fits |
| Synchronization run | ≤ 3 provider requests/s, ~20 statements per Game, one HTTP thread, sequential; hours for a large interval; sustained CPU on a closed-lid laptop | Hypothesis: measure `sensors` and `docker stats` during the first long run; thermal throttling would show as rising latency, not failure |
| Deployment (migration + smoke) | Chromium in the smoke container is the heaviest process on the host for a minute or two | Reasonable risk: fits; already bounded |
| Disk growth | Bounded logs (30 MiB per container), catalogue growth in the low GiB, images a few GiB, backups (once they exist) tens of MiB per day | Current evidence: no concern for years; prune old images (`IR-06`) |

**Conclusion:** the host is sufficient for the application, PostgreSQL, Keycloak,
the approved collector, catalogue synchronization and private use of the MVP, with
margin. No hardware or infrastructure addition is justified. What should be measured
before believing anything stronger: JVM heap and GC during the first synchronization,
PostgreSQL cache hit ratio once the catalogue has real size, CPU temperature during a
sustained run, and SSD SMART attributes quarterly. ADR-0019's "initial 8 GB RAM
remains until runtime measurements justify reconsideration" stands.

---

## 8. Revisit triggers

Recorded so they are not rediscovered; none is current work.

| ID | Revisit … | When … | First move inside the current host |
|---|---|---|---|
| `RT-1` | RAM (ADR-0019) | Measured steady memory use above ~6 GiB, swap activity, or OOM kills in `dmesg` | Lower Keycloak's heap share, check the application heap, then the ADR-0019 hardware trigger |
| `RT-2` | Backup granularity (`IR-01` daily dumps) | A measured or decided RPO below 24 hours | `pg_basebackup` plus WAL archiving to the same off-host destination; still no new component |
| `RT-3` | PostgreSQL major upgrade path | PostgreSQL 19 GA and a decision to move | Dump/restore into a new volume with the existing backup mechanism, then image digest bump; `pg_upgrade` only if the dump grows impractical |
| `RT-4` | Identity hardening (brute-force detection, password policy, admin-console restriction, RP-initiated logout) | Any second tailnet user, any change of release mode, or any non-owner access | Realm settings through the documented realm-change procedure (`IR-04`) |
| `RT-5` | Tailscale dependency | Terms, pricing or eligibility change; Serve certificate or MagicDNS behaviour change; ADR-0019 trigger | Caddy or nginx on loopback with a private CA and the same loopback-only publication; router still closed |
| `RT-6` | A second host or any physical separation | Measured host reliability limits, or a release-mode change (ADR-0019) | Nothing before that; a second host without HA goals is only a warm spare restored from `IR-01` backups |

---

## 9. Things reviewed but intentionally not recommended

- **A secret manager (Vault, SOPS, cloud secret stores).** Ten files in a
  root-owned 0750 directory with per-service Compose grants, generated with
  `openssl rand`, validated against container metadata, are the right size for one
  host and one operator. The gaps are a rotation procedure and backup inclusion,
  both documentation.
- **Terraform, Ansible or another configuration-management tool for the host.**
  The six reconstruction steps are short and the host is one. A clean-room execution
  of those steps (`IR-01`) is worth far more than automating them.
- **Rootless Docker or Podman.** The operator is the owner and root on the box
  anyway; rootless adds friction (loopback publication, `group_add`, volumes) for a
  threat that is out of scope.
- **A reverse proxy (Caddy, nginx, Traefik) in front of the containers.** Tailscale
  Serve already terminates HTTPS with managed certificates and keeps everything
  loopback-only. `RT-5` covers the day Tailscale is no longer suitable.
- **Egress restriction for the `edge` network.** Containers can reach the LAN and
  the Internet; the application needs IGDB and GHCR, Keycloak needs nothing. Filtering
  container egress protects against a compromised container, which the pinned,
  scanned images make an acceptable residual.
- **Disabling self-registration or adding brute-force protection now.** The product
  brief requires delegated registration, the local browser test exercises it, and
  the tailnet has one user. `RT-4` records when this changes.
- **Publishing the management port, even on loopback**, to make synchronization
  easier. `docker compose exec` is enough and keeps the unauthenticated operator
  surface inside Docker (`IR-05`).
- **A UPS, RAID, a second disk, or a new SSD.** Hardware purchases are outside
  ADR-0019; the laptop battery already bridges short outages; backups (`IR-01`) are
  the mitigation for the single disk.
- **A durable telemetry backend, dashboards or alerting.** Deferred by the platform
  design until measured value justifies host cost; `OUT_OF_SCOPE` here in any case.
- **Kubernetes, Swarm, Nomad, a second host, HA, cloud migration.** No finding
  needs any of them and every ADR trigger is unmet.
- **A daily reboot or aggressive automatic updates.** Manual, backup-first reboots
  at a monthly cadence fit a private host better (`IR-06`).
- **`OUT_OF_SCOPE` items noted for their own reviews:** dependency and base-image
  refresh automation and the CI gates that validate `deploy/private-dev`
  (DevOps); the collector's debug-only export and what the smoke asserts about
  telemetry (Observability); the unauthenticated Actuator design and the `cataloguesync`
  endpoint's HTTP shape (Architecture); the application's `apk upgrade` layer
  reproducibility (DevOps, already recorded as `DEL-04` in the earlier review).

---

## 10. Suggested GitHub issues

Only work that justifies tracking; related findings are grouped.

| # | Proposed title | Findings | Priority | Cost | ADR |
|---|---|---|---|---|---|
| 1 | Prove encrypted off-host backup, restore and clean-room host reconstruction for private dev (refines #44) | `IR-01`, `IR-09` (backup as mitigation) | HIGH | medium | no |
| 2 | Pin the runtime to the deployed checkout and harden the PostgreSQL service | `IR-02`, `IR-07`, `IR-08` | MEDIUM | small | no |
| 3 | Write the private-dev host runbook: ingress policy, Keycloak lifecycle, synchronization on dev, monthly maintenance | `IR-03`, `IR-04`, `IR-05`, `IR-06`, `IR-09` | MEDIUM | small | no |

`IR-10` is a measurement to record in the #36/#45 evidence, not an issue. No `RT-*`
trigger becomes an issue.

Suggested order: issue 1 before the first rating is stored on `dev` (that is, before
or immediately after #36 completes); issue 3 next because `IR-05` is needed to put
any catalogue on `dev` and `IR-03` closes the only exposure question the repository
cannot answer; issue 2 whenever the Compose file is next touched.

---

## 11. Final assessment

The infrastructure is adequate, secure for its release mode, simple, and close to
reproducible. It is not yet operable through a full lifecycle: nothing can be
recovered, and the knowledge needed to run the host for a year is partly in a design
document, partly in a README, and partly in issue comments. The single `HIGH`
finding is the one the owner already planned (#44); this review only argues that the
MVP should not be declared closed while the data that would make it a product
(identity and ratings) exists on one unbacked-up laptop disk, and it supplies the
smallest design that meets the approved policy at zero cost.

Everything else is small: record what is already true (checkout revision, firewall
and SSH policy, key expiry, realm lifecycle, maintenance cadence), tighten one
service to match its siblings, and document the one operator command the design
deliberately hides from the network. No new component, host, provider or ADR is
needed, and ADR-0019 and the platform design remain the right decisions; the only
evidence that would reopen them is the measured pressure they themselves name.

What was verified: every configuration file, script and Dockerfile cited, at
`d10bc44`; the recorded owner evidence in #124 and #43; the open state of #36, #44
and #45. What was assumed: that the host's `sshd`, firewall and Tailscale settings
are Ubuntu and Tailscale defaults where the repository and issues say nothing
(`IR-03` asks the owner to confirm); that the owner's product user on `dev` was or
will be created by hand through Keycloak; and that no backup mechanism exists outside
what the repository and #44 describe.
