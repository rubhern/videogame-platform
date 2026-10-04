# Private dev runtime and deployment

This directory is the reviewed operator entry point for the owner-managed private
`dev` host (`vgpdev`). The
[platform and delivery design](../../docs/architecture/deployment/mvp-platform-and-delivery.md)
owns the policy; the files here own the executable details; the
[operations runbook](../../docs/development/operations-runbook.md) records which
procedures have been proven on the host, their evidence boundary, and day-two
operations (synchronization on `dev`, incident handling).

Local use of the same provisioned metrics stack is documented in
[local setup](../../docs/development/local-setup.md#local-metrics-and-dashboards).
`compose.observability.yaml` owns shared metrics service definitions; this directory's
`compose.yaml` and the root local overlay own environment-specific wiring.

## Boundaries

- `compose.yaml` defines digest-pinned PostgreSQL, a locally optimized Keycloak image
  built from a digest-pinned upstream, the bounded OpenTelemetry collector, Prometheus and Grafana, the
  digest-selected application, a one-shot migration actor and a one-shot browser
  smoke runner. Only PostgreSQL, Keycloak and the observability stack start without an explicit
  profile or service selection; the application runtime receives `videogame_app`
  credentials and cannot migrate, and the migration actor receives only
  `videogame_app_migrator` credentials on the internal data network.
- `bin/deploy-private-dev` runs only when the owner invokes it on a host named
  `vgpdev`, rejects tags and other mutable references, holds one host lock, and
  requires both an immutable GHCR digest and the full source revision expected to
  have produced it. The prior application is untouched until the migration actor
  succeeds; every readiness and smoke check is tied to the newly created container.
- Deployment evidence is an atomically updated JSON record outside the checkout
  (timestamps, target, initiator, source revision, image, application and migration
  versions, candidate container ID, completed smoke checks, phase, outcome), with no
  credentials or personal data.

## Host foundation

These are rebuild instructions for the owner-managed host, not an installation log.
Record outcomes and exceptions in the issue that tracks the host; keep addressing and
credentials private.

1. Review existing data with the owner before erasing the target SSD. Check disk
   identity/SMART health, then clean-install Ubuntu Server 24.04 LTS `amd64` with
   OpenSSH and a non-root sudo user; apply package updates and prefer local SSD boot
   in firmware.
2. Allocate the SSD to the root filesystem. If the installer leaves free LVM extents
   (`lsblk -f`, `sudo lvs`, `sudo vgs`), extend the root LV with
   `sudo lvextend -r -l +100%FREE <root-lv>` and verify with `df -h /`.
3. Connect Ethernet with a router DHCP reservation. Install the owner's SSH public
   key, verify a second session, then disable password/root login. Install
   [Tailscale](https://tailscale.com/docs/install/linux) on the host and on Windows
   (not WSL), enroll both in the owner-only tailnet and verify SSH through it. Confirm
   router forwarding/UPnP is off and that administrative access is unreachable from
   outside the tailnet/LAN over IPv4 and IPv6.
4. Keep a closed laptop lid running: a `/etc/systemd/logind.conf.d/` drop-in with
   `HandleLidSwitch=ignore`, `HandleLidSwitchExternalPower=ignore`,
   `HandleLidSwitchDocked=ignore`, `IdleAction=ignore`, and
   `sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`.
   Reboot and test closed-lid access. Record firmware power-restoration support
   instead of assuming recovery after power loss.
5. Install Docker Engine and the Compose plugin from the
   [official apt repository](https://docs.docker.com/engine/install/ubuntu/), then
   `sudo systemctl enable --now docker tailscaled`. After a reboot check
   `systemctl is-active docker tailscaled`, `sudo docker info` and
   `sudo docker compose version`. Do not start the repository stack yet.
6. Install `smartmontools` and `lm-sensors`; record OS/architecture, `free -h`,
   `lsblk`, LVM state, SMART health and `sensors`, and inspect `systemctl --failed`
   and `journalctl -b -p err`. A single idle snapshot does not prove workload
   capacity.

## One-time private host preparation

Do not run these commands from a workstation and do not apply them to `vgpdev`
without the owner's explicit host-change decision. Replace `<operator>` and
`<checkout>` only after verifying them on the host. The host needs Bash, Python 3,
Docker Engine and the Compose plugin; deployment smoke JavaScript runs inside the
dedicated Playwright container, so Node.js is not a host prerequisite.

1. Create the protected runtime configuration and secret files. The protected parent
   directory is the host access boundary; Compose grants containers only the files
   each actor needs.

   ```bash
   sudo groupadd --system --gid 20001 vgp-runtime
   sudo usermod --append --groups vgp-runtime <operator>
   sudo install -d -m 0750 -o root -g vgp-runtime /etc/videogame-platform/dev
   sudo install -m 0640 -o root -g vgp-runtime \
     <checkout>/deploy/private-dev/runtime.env.example \
     /etc/videogame-platform/dev/runtime.env
   sudo <checkout>/deploy/private-dev/bin/prepare-secrets \
     /etc/videogame-platform/dev/secrets vgp-runtime
   sudo install -d -m 0770 -o root -g vgp-runtime \
     /var/lib/videogame-platform/dev/deployment-evidence
   ```

   Start a new login session so group membership applies. Set
   `PRIVATE_DEV_SECRETS_GID` from the real group and replace both example origins with
   the exact `vgpdev` MagicDNS HTTPS origins. Leave the application/source/version
   placeholders unchanged: the deployment command overrides them for one invocation.
   Populate IGDB credentials only for an explicitly initiated catalogue sync. The
   preparation command creates a fixed non-personal smoke username and random password
   in `oidc-smoke-username` and `oidc-smoke-password`; neither value is printed or
   placed in `runtime.env`.

2. Validate the reviewed topology without starting or replacing services, and run
   the independent metrics path check (a disposable metrics stack and bounded synthetic
   OTLP sender; no application is deployed):

   ```bash
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env
   bash scripts/validate-private-dev-runtime.sh --telemetry-smoke
   ```

   The application placeholders are accepted only by the static validator; the
   deployment command rejects them.

3. Start only the runtime dependencies. Do not enable the application or deployment
   profiles here.

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml pull postgres telemetry prometheus grafana alloy loki
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml build --pull keycloak
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml up --detach postgres keycloak telemetry prometheus grafana alloy loki
   ```

4. Keep Tailscale Funnel and router forwarding disabled. Confirm the owner-only
   tailnet policy before configuring the two private HTTPS routes:

   ```bash
   tailscale serve --bg --https=443 http://127.0.0.1:8080
   tailscale serve --bg --https=8443 http://127.0.0.1:8180
   tailscale serve status
   tailscale funnel status
   ```

   Verify the Keycloak discovery issuer through port `8443`, database/role ownership,
   restart persistence, loopback-only listeners, router forwarding/UPnP state and
   unsolicited public IPv4/IPv6 reachability. None of those checks is implied by
   repository validation.

5. After Keycloak and its private HTTPS route are healthy, provision the dedicated
   deployment-smoke account from the protected files:

   ```bash
   deploy/private-dev/bin/provision-oidc-smoke-user \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

   The command is idempotent: it declares the admin-only marker in the realm User
   Profile when missing, then creates the marked, enabled account when absent or
   rotates its protected password. It refuses an unmarked existing username, any
   profile other than its fixed synthetic values, direct client roles, or group
   membership, and never places a password or token in arguments, environment
   metadata, logs, realm imports, or Git. If an earlier attempt left an unmarked
   `vgp-deployment-smoke` account, confirm through the private Admin Console that it
   is the failed non-personal account, delete that one account, and rerun the command;
   never add the marker to adopt it.

## Gameómetro identity theme

Post-MVP (#162), the Keycloak image packages `docker/keycloak/themes/gameometro`
and copies the canonical frontend brand, fonts and night artwork at build time.
Both Compose build contexts are the repository root. The
[frontend design guidelines](../../docs/development/frontend-design.md) own its visual
rules; inherited Keycloak templates retain credential handling and account-flow logic.

After owner review, build/recreate the Keycloak service from the reviewed checkout.
Startup import skips an existing realm: in the private Admin Console, select
`gameometro` as its Login theme, `Gameómetro` as its display name, set the
supported/default locale to Spanish and enable
Forgot password in Login settings. Do not delete identity data or re-import the realm
to apply a theme. Recreate the service to clear its theme cache after changing assets.
To roll back, select the previous built-in login theme and restore the previous realm
settings; no product schema migration is involved.

Password-recovery email requires SMTP configured privately in Keycloak. No mail service,
credentials or paid infrastructure is added here. Rendering a recovery screen does not
prove email delivery; test that separately against the owner's configured transport.

## Metrics dashboards

The [observability guide](../../docs/development/observability.md#private-dev-dashboards)
owns dashboard interpretation and gaps; Compose, Collector, Prometheus and Grafana
files here own all configuration. No dashboard UI setup is required.

For an existing host, rerun `bin/prepare-secrets` with the protected directory and
runtime group above; it preserves populated files and creates the Grafana admin
secret. Validate, pull and start only `telemetry prometheus grafana` from the reviewed
checkout using the dependency commands above. The application image is unaffected.
Do not print the secret or put it in `runtime.env`, command arguments or a URL.
Grafana reads it from its granted file at first database initialization; subsequent
secret-file changes do not rotate the stored password.

From the owner's workstation, open an SSH tunnel over the existing private path:

```bash
ssh -N -L 127.0.0.1:3000:127.0.0.1:3000 vgpdev
```

Open `http://127.0.0.1:3000`, sign in as `owner` using the protected Grafana admin
secret, and select the provisioned **VideoGame Platform** folder. The loopback HTTP hop is
inside the SSH tunnel; do not add a public bind, Tailscale Serve route or Funnel.

Run `validate-private-dev-runtime.sh --telemetry-smoke` for disposable configuration,
OTLP handoff, authenticated datasource/panel queries, anonymous denial, clean
provisioning and recreation/persistence checks. Synthetic samples never go into the
live project, even when an environment file is supplied. Run the existing validator
with `--env-file <protected-runtime.env> --live` after real product activity and at
least one synchronization run for read-only host checks and a resource snapshot.
It requires a finite result in each dashboard category; absent synchronization data
is an evidence gap, not an instruction to fabricate it. Allow several export/scrape
intervals for rates. Capture another resource snapshot during normal browsing and
an owner-triggered bounded sync, as well as at idle.

For host persistence evidence, note a query timestamp and result, recreate only
Prometheus and Grafana with `up --detach --no-deps --force-recreate prometheus grafana`,
and query the original timestamp through Grafana again. Do not use `down --volumes`:
the same project also owns irreplaceable PostgreSQL data. To recover from a metrics
configuration failure, stop only Prometheus/Grafana and restore the prior Collector
configuration; product readiness and requests must continue. Reapply the reviewed
configuration to resume telemetry. Metrics volumes are disposable but intentional
history deletion needs an explicit target review. Leave PostgreSQL volumes alone.

Host acceptance also checks loopback access from the owner workstation, no OTLP,
Collector handoff or Prometheus IPv4/IPv6 listener, representative real-data panels,
retained samples after recreation, and continued readiness/product reads during a
bounded metrics-stack outage. Record measured idle/load CPU, memory and PIDs plus
metrics-volume disk usage in #158; the repository smoke does not prove host capacity.

## Log aggregation and exploration

Post-MVP #159 is prepared in the repository; rollout/acceptance on `vgpdev` is not
implied. Review [ADR-0021](../../docs/decisions/0021-collect-private-dev-logs-with-alloy-and-loki.md)
and the [platform storage/capacity policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md)
before changing the host. [Observability](../../docs/development/observability.md#private-dev-log-exploration)
owns log interpretation and queries. Compose, Alloy, Loki and provisioning files
own executable settings. No new secret, paid resource or external service is needed.

First run the static runtime validator and the independent logging smoke:

```bash
bash scripts/validate-private-dev-runtime.sh --env-file /etc/videogame-platform/dev/runtime.env
python3 scripts/private-dev-logs-check.py --smoke
```

The smoke generates its own project, credentials, volumes, loopback UDP port and
structured fixtures. It never consumes the host runtime file or starts application,
identity or database services. It proves configuration and synthetic behaviour only.
Do not run concurrent heavy validation/deployment work on the constrained host.

### Reviewed rollout

1. Check the storage/capacity stop thresholds before pulling or starting anything.
   Review `free -h`, `df -h /`, `docker stats --no-stream` and `docker system df -v`.
   Keep the existing application digest and full source revision: this configuration
   change needs no new product image or migration.
2. From the reviewed checkout, pull/start only logging and recreate Grafana to load
   its additional datasource; the shared metrics datasource and dashboards remain:

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml pull alloy loki
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml up --detach --no-deps alloy loki grafana
   ```

3. The application logging driver changes only when its container is recreated.
   Use the existing owner-triggered deployment procedure with the **same reviewed
   deployed digest and matching source revision**, or defer collection until the
   next approved deployment. This does not authorize selecting a different image.
   That controlled recreation runs the established migration/readiness/smoke and
   evidence checks; do not recreate with runtime placeholders. Earlier local logs
   are not backfilled and disappear with the removed application container.
4. Use the existing Grafana SSH tunnel, select **Platform logs** in Explore and
   search a known normal request correlation ID. Inspect the unmodified ECS body,
   WARN/ERROR/event searches and the two indexed labels. Complete JSON only is
   aggregated; inspect plain pre-logger output with Docker. Keep DEBUG disabled. Check
   `docker compose ... logs application` still supplies bounded local inspection.
5. Run `validate-private-dev-runtime.sh --env-file <runtime.env> --live` for the
   existing static/live isolation and resource snapshot checks. Independently inspect
   the UDP listener with `ss -4 -lnu` and `ss -6 -lnu`, Docker port mappings, and
   external IPv4/IPv6 reachability: no public syslog receiver or Loki/Alloy API.
   Leave the two existing Tailscale HTTPS routes, Funnel and router policy unchanged.

### Capacity, retention and failure evidence

Get the real Loki storage path without assuming Docker's data root:

```bash
logging_container=$(docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  --file deploy/private-dev/compose.yaml ps --quiet loki)
logging_storage=$(docker inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/loki"}}{{.Source}}{{end}}{{end}}' "$logging_container")
[[ -n "$logging_storage" ]] && sudo du -sh -- "$logging_storage"
df -h /
docker stats --no-stream
```

Inspect usage at idle, during normal browsing and an owner-triggered bounded sync,
and again after the retention period plus index/compactor deletion lag. Record in
#159 how usage changes, container CPU/memory/PIDs, source/send failures and rejected
lines/ingestion. Retention is asynchronous: confirm eventual physical deletion from
volume use and compactor behaviour, not just absence from queries (lookback hides
expired entries). Do not shorten production retention or inject artificial samples
into live Loki to manufacture evidence. No automatic capacity alert is added.

If either platform disk threshold is crossed, stop **only Alloy and Loki** before
further ingestion; do not stop product/database services or prune Docker globally.
Diagnose Loki/Alloy logs, host free space and the exact Loki volume. Reclaim unrelated
storage only through its owner's procedure. Resume with the reviewed configuration
only after restoring headroom; unexpectedly fast growth requires a retention/rate
review. A hard filesystem quota is not present in this initial configuration.

For persistence, note a recent query timestamp/body, recreate only Loki/Grafana with
`up --detach --no-deps --force-recreate loki grafana`, then search that same timestamp.
For independence, stop Alloy briefly while making a normal product read and checking
readiness, then start it again and verify new events arrive. Loss during the outage
is expected; restart does not replay missed Docker logs. Collector health alone does
not prove successful delivery. Docker's remote-driver errors can also prevent local
cache writes, so the cache is not an independent durable audit copy.

### Rollback and safe history recovery

For configuration rollback, restore the previously reviewed checkout/configuration
and deploy the same compatible application digest through the existing procedure to
restore its prior local logging driver. Stop Alloy/Loki and recreate Grafana from the
prior provisioning. Keep metrics and PostgreSQL running; no product schema change is
involved. Logging configuration changes apply on recreation, not restart.

If Loki history is corrupt or disposable history must be purged, first stop Alloy
and Loki, inspect the Loki container's mounts, and explicitly review the exact
`loki-data` volume target. Remove only the Loki container, then only that verified
volume; let `up --detach --no-deps loki alloy` recreate it. Never run private-dev
`down --volumes`, `docker volume prune`, delete a guessed host path or touch
PostgreSQL, Prometheus or Grafana volumes. Lost log history has no backup and cannot
be recovered; source logs survive only within their own remaining cache/container.
Record the action and the absence of an automatic recovery claim in #159.

## Owner-triggered deployment

Choose a trusted `main` source revision and review its successful required checks,
image publication summary, scan/SBOM evidence and immutable digest. Both selected
values stay explicit in the command:

```bash
deploy/private-dev/bin/deploy-private-dev \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --image ghcr.io/rubhern/videogame-platform@sha256:<approved-64-hex-digest> \
  --source-revision <approved-full-40-character-main-sha> \
  --initiator rubhern
```

Under one non-blocking host lock the command:

1. validates that the target is `vgpdev`, the protected runtime configuration
   renders, smoke credentials exist, dependencies are healthy, and evidence storage
   is writable;
2. resolves the supplied revision's GHCR tag for verification only, requires its
   published digest to equal the supplied digest, pulls by digest, and verifies the
   image's source, revision and version OCI labels;
3. builds the digest-pinned, no-retry Playwright smoke runner before changing the
   database or application;
4. runs the selected image once as `videogame_app_migrator`, applies and validates
   only packaged production Flyway migrations, and stops immediately on failure;
5. force-recreates only the application service with the selected digest, waits for
   candidate health, and verifies the created container uses the inspected image;
6. runs the deployment smoke against that candidate and the private HTTPS boundary,
   then verifies structured correlation/trace evidence and collector receipt;
7. finalizes the JSON evidence record as `success`, or as `failure` with the phase
   that stopped. A failed outcome is never changed to success because another
   container or older version answers health checks.

The smoke checks management liveness/readiness; `/actuator/info` version and source
revision; the HTTP/JVM/JDBC metric catalogue; `GET /api/v1/releases` returning a
valid local page (including zero items) or the approved `CATALOGUE_NOT_READY`
response; the matching Spanish shell rendering in Chromium with IGDB hosts blocked;
real Keycloak authorization with an opaque `HttpOnly`, `Secure`, `SameSite=Lax` BFF
session, no browser-stored OAuth material, and CSRF-protected logout; W3C
trace/correlation propagation in structured logs; and trace receipt by the collector.
It deliberately does not rate a game, traverse every screen, or run provider
synchronization, and it is not product acceptance.

## Backup, restore, rollback and host-loss recovery

These are the owner-triggered recovery controls. Repository validation
(`scripts/test-private-dev-backup-recovery.sh`) proves only the encryption,
integrity, retention and decision logic; every step that reads the live database or
a real host is environment evidence recorded in the runbook.

### State model

The irreplaceable durable state is the two PostgreSQL databases: `videogame_platform`
(application state and product-owned curation) and `videogame_keycloak` (identity
configuration and runtime accounts such as the deployment-smoke user). Both are
captured as logical `pg_dump` custom-format artifacts. Catalogue/provider data is
reconstructable by IGDB synchronization. Roles and passwords are **not** backed up:
`docker/postgres/init` bootstraps them from the protected secret files when a clean
data volume initializes, which is why a backup never stores a credential.

### One-time backup key setup

Generate the backup keypair on a trusted machine that is not the host, keep the
private key and passphrase offline, and import only the public key on `vgpdev`, so
host compromise cannot expose backup contents. `<recipient>` is the key's email or
fingerprint.

```bash
# On the offline owner machine (once): create the keypair and export the public key.
gpg --full-generate-key
gpg --armor --export <recipient> > vgp-backup-public.asc

# On vgpdev (once): import only the public key into a dedicated keyring.
install -d -m 0700 /etc/videogame-platform/dev/backup-gnupg
gpg --homedir /etc/videogame-platform/dev/backup-gnupg --import vgp-backup-public.asc
```

### Encrypted backup with integrity and retention

Runs on `vgpdev` while the dependency stack is healthy. It dumps both databases,
encrypts each to the public key, writes a manifest and `SHA256SUMS`, self-verifies,
and prunes older backups to the retention count. Copying the destination off the host
(for example over Tailscale with `rsync`) is the owner action that makes the backup
"outside the host".

```bash
deploy/private-dev/bin/backup-private-dev \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --gnupg-home /etc/videogame-platform/dev/backup-gnupg \
  --recipient <recipient> \
  --destination /mnt/vgp-backups \
  --initiator rubhern \
  --keep 7
```

Integrity and retention are re-checkable anywhere, without Docker or the private key:

```bash
deploy/private-dev/bin/verify-private-dev-backup --backup /mnt/vgp-backups/<backup-id>
deploy/private-dev/bin/verify-private-dev-backup --backups-root /mnt/vgp-backups --expect-at-least 7
```

### Isolated restore

Restores a verified backup into a **distinct** Compose project, never the live one.
It rebuilds a clean database volume (destroying only that isolated project's
volumes), bootstraps roles from the protected secret files, streams each decrypted
dump into `pg_restore`, and verifies the restored schema, catalogue tables and
Keycloak realm/account counts. It requires the private key in `--gnupg-home` and
explicit destructive confirmation.

```bash
deploy/private-dev/bin/restore-private-dev \
  --backup /mnt/vgp-backups/<backup-id> \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --gnupg-home /etc/videogame-platform/dev/restore-gnupg \
  --isolated-project vgp-restore-rehearsal \
  --confirm-destroy-isolated-target \
  --evidence-directory /var/lib/videogame-platform/dev/deployment-evidence
```

Tear the rehearsal down afterwards with
`docker compose ... --project-name vgp-restore-rehearsal down --volumes`.

### Rollback versus forward fix

Before recovering a bad deployment, decide whether an older image is still
schema-compatible. The assessment compares the applied Flyway version with the
candidate's packaged migration version and enforces that an applied migration is
never reverted.

```bash
deploy/private-dev/bin/assess-recovery-strategy \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --target-migration-version <candidate-image-flyway-version> \
  --target-image ghcr.io/rubhern/videogame-platform@sha256:<digest>
```

`ROLLBACK` redeploys the older digest with the normal deployment command (its
migration step finds nothing to apply). `FORWARD_FIX` means building and deploying a
corrected revision. `REFUSE` means the inputs are inconsistent; do nothing until they
are explained.

### Host-loss recovery

Recovery does not depend on the original hardware; any compatible Linux host is
sufficient:

1. Rebuild the host foundation and run the one-time preparation above.
2. Restore the latest verified backup with `restore-private-dev`, naming the live
   project as the isolated project on the fresh host (there is no other stack to
   protect), or a rehearsal project first to validate the backup.
3. Deploy the last-good image digest with `deploy-private-dev` and confirm the smoke
   and readiness checks pass.
4. Record the recovery decision, backup id, image digest, migration version and
   outcomes from the generated evidence records.
