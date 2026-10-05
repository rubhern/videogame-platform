# Private dev runtime and deployment

This directory is the reviewed operator entry point for the owner-managed private
`dev` host (`vgpdev`). The
[platform and delivery design](../../docs/architecture/deployment/mvp-platform-and-delivery.md)
owns the policy; the files here own the executable details; the
[operations runbook](../../docs/development/operations-runbook.md) records which
procedures have been proven on the host, their evidence boundary, and day-two
operations (synchronization on `dev`, incident handling).

Local use of the same provisioned metrics stack is documented in
[local setup](../../docs/development/local-setup.md#local-metrics-and-logs).
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
runtime group above; it preserves populated files and creates separate Grafana admin
and database-reader secrets. Roll out the reviewed application migrations before
starting the new SQL panels, then provision the view-only role from the checkout:

```bash
bash scripts/provision-grafana-reader.sh --private-dev /etc/videogame-platform/dev/runtime.env
```

Validate, pull and start `telemetry prometheus grafana` using the dependency commands
above, alongside the reviewed application image that supplies the new instrumentation.
Recreate those metrics services with `--no-deps --force-recreate` when their mounted
Collector/Prometheus/datasource configuration changes; a normal `up` does not reload
that configuration. Dashboard JSON alone is watched by Grafana’s file provider.
Keep the named volumes so metric history and Grafana state survive.
The role procedure is idempotent and aligns the database password with its protected
file; restart Grafana after rotating that file. Do not print either secret or put it
in `runtime.env`, command arguments or a URL. Grafana reads its admin secret at first
database initialization; replacing that file does not rotate the stored admin password.
SQL unavailable before migration/role setup means unavailable panels, not empty data.
To recover, restore prior Grafana provisioning and stop using the reader; the additive
views can remain until a separately reviewed forward removal. Product data is unchanged.

From the owner's workstation, open an SSH tunnel over the existing private path:

```bash
ssh -N -L 127.0.0.1:3000:127.0.0.1:3000 vgpdev
```

Open `http://127.0.0.1:3000`, sign in as `owner` using the protected Grafana admin
secret, and select the provisioned **VideoGame Platform** folder. The loopback HTTP hop is
inside the SSH tunnel; do not add a public bind, Tailscale Serve route or Funnel.

Run `validate-private-dev-runtime.sh --telemetry-smoke` for disposable configuration and a PostgreSQL read-model fixture,
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

The direct host command remains the proven deployment/recovery entry point.
The [owner-approved Actions promotion](#owner-approved-actions-promotion) invokes
it through restricted SSH after verifying CI evidence; neither path deploys on merge.

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
revision; the matching single frontend startup console message; the HTTP/JVM/JDBC metric catalogue; `GET /api/v1/releases` returning a
valid local page (including zero items) or the approved `CATALOGUE_NOT_READY`
response; the matching Spanish shell rendering in Chromium with IGDB hosts blocked;
real Keycloak authorization with an opaque `HttpOnly`, `Secure`, `SameSite=Lax` BFF
session, no browser-stored OAuth material, and CSRF-protected logout; W3C
trace/correlation propagation in structured logs; and trace receipt by the collector.
It deliberately does not rate a game, traverse every screen, or run provider
synchronization, and it is not product acceptance.

## Owner-approved Actions promotion

The original promotion path has successful real-host evidence on #160. The refined
single-dispatch flow still requires owner review, the environment Settings change below
and acceptance on its published main revision. New host changes retain their separate
owner approval; workflow dispatch authorizes only application promotion. The
[platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#artefact-and-delivery)
owns the application/runtime boundary and
[ADR-0023](../../docs/decisions/0023-automate-owner-approved-private-dev-application-promotion.md)
records the accepted trust decision. The workflow and scripts own job mechanics.

### Protected GitHub and tailnet setup

Use the public repository's eligible free GitHub-hosted runner/environment and
existing free personal tailnet; recheck eligibility before enabling. No paid fallback
or additional runtime service is authorized.

Configure the GitHub **dev** environment without required reviewers, wait timers or
custom deployment approval rules. **Run workflow is the sole human approval**.
Keep administrator bypass disabled and permit only the selected **main branch**
(no tags). The workflow checks this policy before the environment job and again
before private connectivity, refusing the old reviewer configuration rather than
waiting for another approval.

For an existing environment, the owner must open **Repository Settings → Environments
→ dev → Deployment protection rules**, remove **rubhern** from **Required reviewers**
and disable that rule, then save. Remove any wait timer or custom approval rule if
configured. In **Deployment branches and tags**, retain **Selected branches and tags**
with exactly the **main branch** and no tags; keep administrator bypass disabled.
Do not delete/recreate the environment or move its secrets. This is an explicit owner
Settings change; repository code does not change it. Verify the saved policy before
dispatching. Existing host/key/tailnet configuration remains required.

Keep all five credentials/connection values as environment secrets, never repository
secrets or workflow inputs:

| Environment secret | Owner-provided value |
|---|---|
| `PRIVATE_DEV_TS_CLIENT_ID` | Dedicated Tailscale OAuth client ID |
| `PRIVATE_DEV_TS_CLIENT_SECRET` | OAuth secret limited to writable auth keys and `tag:vgp-deploy` |
| `PRIVATE_DEV_SSH_HOST` | Exact private `vgpdev.*.ts.net` hostname |
| `PRIVATE_DEV_SSH_KEY` | Dedicated Ed25519 private key for the restricted account |
| `PRIVATE_DEV_SSH_KNOWN_HOSTS` | Exact hostname plus trusted host public key, verified through the existing owner connection/console |

Generate the deployment key on the owner's trusted workstation, store the private
key only in the protected environment, and install only its public key on the host.
Do not discover/trust a new host key during deployment. For rotation, install the
new public key through owner administration, update the environment secret, verify
the restricted path, then remove the old key. Revoke the OAuth client and SSH key to
disable automation; preserve the normal owner administration/recovery path.

The [Tailscale Action](https://github.com/tailscale/github-action) creates an
ephemeral tagged node and logs out in cleanup. Give that tag only TCP 22 to the
specific existing host. Audit all existing grants/ACLs: an earlier allow-all rule
would defeat a narrow added grant. It must reach neither another host nor product
HTTPS, Keycloak, Grafana or management services. Keep owner access and Serve routes
intact. Use ordinary OpenSSH, without enabling Tailscale SSH, Funnel, router
forwarding or a public listener. Validate positive TCP-22 and negative other-service
policy tests before adding the credential.

### Restricted host installation

Through the existing owner administration connection, install a **standalone,
root-owned clean Git checkout** of the reviewed main revision at
`/opt/videogame-platform`. Keep its Git metadata root-owned too, without group/world
write access; do not copy a worktree's `.git` pointer, build outputs or private files.
The runner never updates this checkout. Git and Python 3 are host prerequisites.

Create a dedicated `vgp-deploy` system account with `/bin/sh` only for sshd's
forced-command invocation, no password authentication, no Docker/runtime group
membership and no other sudo rights. Install its public key in root-owned
`/etc/ssh/authorized_keys/vgp-deploy` with mode 0644 (the key is public), under a
root-owned searchable directory, using the `restrict` authorized-key option.
The account must not be able to replace its authorized keys.

Add this reviewed sshd drop-in; run `sudo sshd -t`, keep a working owner session
and reload SSH only after the configuration validates:

```text
Match User vgp-deploy
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AuthorizedKeysFile /etc/ssh/authorized_keys/%u
    ForceCommand /usr/bin/sudo -n /opt/videogame-platform/deploy/private-dev/bin/promote-private-dev
    DisableForwarding yes
    PermitTTY no
    PermitUserRC no
Match all
```

Install a root-owned mode-0440 sudoers drop-in and validate it with `visudo -cf`
before enabling the key. The empty argument specification permits no arguments:

```text
Defaults:vgp-deploy env_keep += "SSH_ORIGINAL_COMMAND"
vgp-deploy ALL=(root) NOPASSWD: /opt/videogame-platform/deploy/private-dev/bin/promote-private-dev ""
```

The isolated Python forced command validates its strict request grammar, clears the
inherited environment and checks privileged code ownership before loading repository
code. It has fixed runtime/evidence paths and cannot accept a shell, file upload,
alternate checkout, environment file or Compose operation.

Preserve the same deployment lock for root automation and the owner operator.
Configure a reviewed tmpfiles entry and create it before first promotion:

```text
f /run/lock/videogame-platform-dev-deployment.lock 0660 root vgp-runtime - -
```

After reviewing/applying the actual runtime and dependency configuration using its
existing explicit procedures, acknowledge its clean installed Git contract:

```bash
sudo python3 /opt/videogame-platform/scripts/private_dev_promotion.py --installed-contract \
  | sudo tee /etc/videogame-platform/dev/deployment-contract.sha256 >/dev/null
sudo chown root:vgp-runtime /etc/videogame-platform/dev/deployment-contract.sha256
sudo chmod 0640 /etc/videogame-platform/dev/deployment-contract.sha256
```

This acknowledgement is an owner statement that the runtime was reviewed/applied;
writing the hash does not deploy a dependency. Stop rather than acknowledge a rollout
that has not happened. A tooling-only change may need just an approved checkout
update; review the diff to distinguish that from real service changes. Preserve
private runtime/secrets under `/etc`, evidence under `/var/lib`, and bind-mounted
runtime configuration at its reviewed location. File replacement/restart alone may
not update imported Keycloak state, database roles or Grafana's persisted credentials.

### Promote and verify

After the complete trusted-main build/security gates and publication succeed, open
**Actions → Promote application to private dev → Run workflow**, select **main** and
press **Run workflow**. There are no artifact/run input fields and no second approval.
The owner dispatch authorizes deploying the exact main SHA captured by GitHub for
that invocation. Checkout and artifact selection remain pinned to that SHA even if
main advances while the run is queued; dispatching before evidence is ready fails.

The workflow derives the unique main `push` Build and verify run, its current attempt
and the immutable OCI digest from its retained `application-image-publication-<SHA>`
record. It does not choose a latest image or fall back to a different SHA, older attempt
or older successful run. Absent, expired, mismatched or ambiguous evidence refuses
promotion; multiple build runs for that SHA are conservatively refused. It checks
quality/security gates, main ancestry and the deployment contract, revalidates the
same derived tuple before connectivity, and the host independently rechecks source
trust and digest/OCI binding. No build occurs during promotion.

Pending Actions runs are serialized but the queue is not FIFO; the existing host
lock also refuses concurrent manual deployment.

This refinement changes the installed deployment tooling contract: update the reviewed
root-owned host checkout and acknowledge it through the existing procedure before
the first refined promotion. Review the diff first; this does not authorize a runtime
dependency upgrade. Leaving the old contract installed deliberately refuses promotion.

The host rechecks public main CI/source evidence and binds the candidate's runtime
fingerprint to the installed and acknowledged contract. Existing dependencies must
already be healthy. A different contract or active application Compose overlay stops
before migration/activation. This initial workflow supports the base private-dev
application in the fixed `videogame-platform-dev` Compose project only; the localization overlay retains its
[explicit operator path](#catalogue-localization-helper).

Read the Actions summary and retained `private-dev-promotion-*` receipt. Original
deployment JSON and detailed log remain under the protected evidence root in a
per-invocation directory; use owner administration/sudo to inspect them. Failed SSH,
missing receipts or workflow timeout are failures, never proof that deployment stopped
or succeeded. Inspect the host before another promotion, and follow the existing
[recovery assessment](#rollback-versus-forward-fix); no automatic rollback is added.
GitHub public API rate limits, unavailable/expired CI artifacts or private-network
failure refuse promotion; there is no retry that converts uncertain deployment into
success.

Open the application, enable DevTools Console info messages, disable browser network
cache and reload. Confirm the
[frontend build identity](../../frontend/README.md#deployed-build-identity)
matches the expected version and SHA prefix. Check runtime container identities are
unchanged. Record real-host restriction, success/failure and recovery acceptance in
#160 as required by the
[runbook](../../docs/development/operations-runbook.md#deploying-an-immutable-digest).

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

## Catalogue localization helper

#235 adds an optional private acquisition container; its implementation is awaiting
owner review and host acceptance. Apply the normal migration/application deployment
first. Convert/install the selected immutable model using the
[backend model procedure](../../backend/README.md#catalogue-translation-runtime-and-models).
Set `CATALOGUE_TRANSLATION_MODEL_DIR` to its absolute host directory in protected
`runtime.env`; retain the existing pinned application image and version entries.

```bash
docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  -f deploy/private-dev/compose.yaml -f deploy/private-dev/compose.localization.yaml \
  --profile application --profile localization up -d --build catalogue-localizer application
```

The overlay sets the application's private helper address. It uses the existing
internal data network, publishes no port, mounts weights read-only, and runs as a
non-root user with explicit resource limits. It needs no secret or persistent volume.
The normal application rollout does not build/start it implicitly. Do not add it to
Tailscale Serve, the browser edge, application readiness or the database backup set.

For update/rollback, select the new/previous immutable model directory and recreate
`catalogue-localizer` through the same overlay. To suspend inference, stop only that
service; local reads and valid synchronization keep working, and missing/outdated
content remains eligible for a later backfill. Recreate the application from the base
Compose file to remove its helper override. Preserve model manifests/licences with
installed weights. The [operations runbook](../../docs/development/operations-runbook.md#catalogue-localization)
owns retry commands, diagnostics and the unexecuted host acceptance.
