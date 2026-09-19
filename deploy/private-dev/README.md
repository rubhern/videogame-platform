# Private dev runtime and deployment

This directory is the reviewed operator entry point for the owner-managed private
`dev` environment. It contains the runtime prepared by #43 and the owner-triggered
deployment mechanism prepared by #36. Repository validation is not evidence that
either has run successfully on `vgpdev`.

The canonical environment, deployment and failure policy is the
[platform and delivery design](../../docs/architecture/deployment/mvp-platform-and-delivery.md).
The files here own the executable details. The
[operations runbook](../../docs/development/operations-runbook.md) records which of
these procedures have been executed on `vgpdev`, their evidence boundary, and the
day-two operations (synchronization on `dev`, incident handling) that this README
does not cover.

## Boundaries

- `compose.yaml` defines digest-pinned PostgreSQL, a locally optimized Keycloak image
  built from a digest-pinned upstream, bounded OpenTelemetry collection, the
  digest-selected application, a one-shot migration actor and a one-shot browser
  smoke runner. Only PostgreSQL, Keycloak and telemetry start without an explicit
  application/deployment profile or service selection.
- PostgreSQL and telemetry have no host ports. Keycloak and the application bind only
  to IPv4 loopback; Tailscale Serve remains the private HTTPS edge. Management and
  OTLP ports stay inside Docker networks.
- The application runtime receives `videogame_app` credentials and cannot migrate.
  The one-shot migration actor receives only `videogame_app_migrator` credentials,
  joins only the internal data network and exits before application replacement.
  Keycloak keeps its separate database and role.
- `bin/deploy-private-dev` runs only when the owner explicitly invokes it on a host
  named `vgpdev`. It rejects tags and other mutable references, holds one host lock,
  and requires both an immutable GHCR digest and the full source revision expected
  to have produced it. No workflow deploys from `main` automatically.
- The source-revision tag is used only to verify the supplied digest. The script then
  pulls and runs the digest, verifies the selected-platform OCI source/version labels,
  and never derives a candidate on the owner's behalf.
- The prior application remains untouched until the migration actor succeeds. A
  migration failure prevents replacement. Candidate readiness and every smoke check
  are tied to the newly created Compose container; any failure records a failed
  deployment. Automatic rollback is out of scope; the owner-triggered backup, restore,
  rollback-assessment and host-loss recovery controls are documented below.
- Deployment evidence is an atomically updated JSON record outside the checkout. It
  includes timestamps, target/environment, initiator, source revision, immutable
  image, application version, migration version, candidate container ID, completed
  smoke checks, phase and outcome. It contains no credentials or personal data.
- Deployment health never invokes IGDB or requires catalogue synchronization. A
  published empty release page and the approved `CATALOGUE_NOT_READY` state for a
  database with no publication both prove the local read/browser boundary; provider
  image hosts are blocked inside the smoke runner.

## One-time private host preparation

Do not run these commands from a workstation and do not apply them to `vgpdev`
without the owner's explicit host-change decision. Replace `<operator>` and
`<checkout>` only after verifying them on the host.

The host-side commands require Bash, Python 3, Docker Engine and the Docker Compose
plugin. Node.js is not a host prerequisite: repository JavaScript checks run in the
repository/CI validation mode, while deployment smoke JavaScript runs inside the
dedicated Playwright container.

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
   placed in `runtime.env`. The account profile uses only the fixed synthetic values
   `Deployment`, `Smoke`, and `vgp-deployment-smoke@example.invalid` to satisfy the
   realm's normal required-profile policy.

2. Validate the reviewed topology without starting or replacing services:

   ```bash
   bash scripts/validate-private-dev-runtime.sh \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

   The application placeholders are accepted only by this static validator. The
   deployment command will reject them. The independent `--telemetry-smoke` mode
   starts a disposable collector and one fixed, non-personal OTLP sender; it does not
   deploy an application:

   ```bash
   bash scripts/validate-private-dev-runtime.sh --telemetry-smoke
   ```

3. Start only the runtime dependencies prepared by #43. Do not enable the application
   or deployment profiles here.

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml pull postgres telemetry
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml build --pull keycloak
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml up --detach postgres keycloak telemetry
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
   restart persistence, loopback-only listeners, router IPv4 forwarding/UPnP state
   and unsolicited public IPv4/IPv6 reachability as described by the platform design
   and #43 evidence. None of those checks is implied by repository validation.

5. After Keycloak and its private HTTPS route are healthy, provision the dedicated
   deployment-smoke account from the protected files:

   ```bash
   deploy/private-dev/bin/provision-oidc-smoke-user \
     --env-file /etc/videogame-platform/dev/runtime.env
   ```

   The command is idempotent: it declares the dedicated marker in the realm User
   Profile when an already-initialized realm lacks it, then creates the marked,
   enabled account when absent or rotates its protected password. The marker remains
   managed with unmanaged attributes disabled and is visible/editable only to Keycloak
   administrators. The command refuses an unmarked existing username, any profile
   other than its fixed synthetic values, direct client roles, or group membership. It
   upgrades only the earlier marked account whose three profile fields are all absent;
   a partial or different profile remains rejected. It uses Keycloak's private HTTPS
   Admin API without putting the admin password, smoke
   password, or access token in arguments, environment metadata, logs, realm imports,
   or Git. Private dev continues
   to exclude the synthetic local-user import used by disposable development tests.

   If a pre-fix attempt left an unmarked `vgp-deployment-smoke` account, do not add the
   marker to adopt it. Through the private Keycloak Admin Console, first confirm that
   the exact username is the failed non-personal account and has only the fixed
   synthetic profile values, no direct client roles, and no groups; then delete that
   one account and rerun the command.
   This preserves the refusal rule for any unrelated account that happens to use the
   protected username.

## Owner-triggered deployment

Choose a trusted `main` source revision and review its successful required checks,
image publication summary, scan/SBOM evidence and immutable digest. The two selected
values remain explicit in the command:

```bash
deploy/private-dev/bin/deploy-private-dev \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --image ghcr.io/rubhern/videogame-platform@sha256:<approved-64-hex-digest> \
  --source-revision <approved-full-40-character-main-sha> \
  --initiator rubhern
```

The command performs this exact sequence under one non-blocking host lock:

1. validate that the target is `vgpdev`, the protected runtime configuration renders,
   smoke credentials exist, dependencies are running/healthy, and evidence storage is
   writable;
2. resolve the explicitly supplied source revision's GHCR tag for verification only,
   require its published digest to equal the supplied digest, pull by digest, and
   verify the local image's source, revision and version OCI labels;
3. build the digest-pinned, no-retry Playwright smoke runner before changing the
   database or application;
4. run the selected application image once as `videogame_app_migrator`, apply and
   validate only packaged production Flyway migrations, record the current migration
   version, and stop immediately on failure;
5. force-recreate only the application service with the selected digest, wait for
   candidate health, and verify the created container uses the inspected local image;
6. run the complete deployment smoke against that candidate and the private HTTPS
   boundary, then verify structured correlation/trace evidence and collector receipt;
7. finalize the JSON evidence record as `success`, or as `failure` with the phase that
   stopped. A failed outcome is never changed to success because another container or
   older version happens to answer health checks.

Every normal deployment checks:

- management liveness and readiness;
- `/actuator/info` application version and full source revision;
- the management metric catalogue for HTTP, JVM and JDBC diagnostics;
- `GET /api/v1/releases` returning either a valid local publication page (including
  zero items) or the approved `CATALOGUE_NOT_READY` response when no publication has
  ever existed;
- the corresponding Spanish release, empty, or catalogue-not-ready shell rendering
  in Chromium, with IGDB hosts blocked;
- real Keycloak authorization, an opaque `HttpOnly`, `Secure`, `SameSite=Lax` BFF
  session, absence of browser-stored OAuth material, and CSRF-protected logout;
- W3C trace/correlation propagation in structured application logs; and
- trace receipt by the bounded OpenTelemetry collector.

The smoke deliberately does not rate a game, traverse every screen, run or require
provider synchronization, or claim full MVP acceptance. Catalogue synchronization
stays a separate owner-triggered operation; the complete journey remains #45.

## First real deployment evidence still required

The first approved execution on `vgpdev` must still establish host facts that cannot
be proved in this repository:

- the selected revision's current required GitHub checks and publication evidence are
  accepted by the owner before invoking the command;
- #43 dependency, private HTTPS, secret, role, resource and no-public-ingress checks
  remain valid with the application reservation active;
- the releases API/browser evidence records either a valid local publication (which
  may be empty) or the approved no-publication state; no catalogue synchronization or
  IGDB availability is a deployment prerequisite;
- the dedicated smoke account completes the real private Keycloak flow with only its
  fixed synthetic profile, no admin role, and no personal data;
- the migration/application/container versions and all smoke/telemetry checks appear
  in the generated evidence record; and
- representative post-deployment CPU, memory, disk, listener and private-access
  observations are recorded without calling this a named release or full MVP
  acceptance.

This deployment procedure makes no rollback, backup/restore or host-loss claim. Those
controls are owned by the next section.

## Backup, restore, rollback and host-loss recovery

These are the owner-triggered recovery controls. The
[platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md) owns
the policy and the state model; the scripts here own the mechanics. Repository
validation proves only the encryption, integrity, retention and decision logic through
`scripts/test-private-dev-backup-recovery.sh`; every step that reads the live database
or a real host is environment evidence.

### State model

The irreplaceable durable state is the two PostgreSQL databases: `videogame_platform`
(application state and product-owned curation) and `videogame_keycloak` (identity
configuration and runtime accounts such as the deployment-smoke user). Both are captured
as logical `pg_dump` custom-format artifacts. Catalogue/provider data is reconstructable
by IGDB synchronization and is not treated as durable. Roles and passwords are **not**
backed up: they are bootstrapped from the protected secret files by
`docker/postgres/init` when a clean data volume initializes, which is why a backup can be
restored without ever storing a credential.

### One-time backup key setup

Generate the backup keypair on a trusted machine that is not the host, keep the private
key and its passphrase offline, and import only the public key on `vgpdev`. The host can
then encrypt new backups but can never read existing ones, so host compromise does not
expose backup contents. `<recipient>` is the key's email or fingerprint.

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
encrypts each to the public key, writes a manifest and `SHA256SUMS`, self-verifies, and
prunes older backups to the retention count. Point `--destination` at an off-host or
externally mounted location; the artifacts are public-key encrypted, so even an untrusted
destination cannot read them. Physically copying the destination off the host (for
example over Tailscale with `rsync`) is the owner action that satisfies "outside the
host".

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

Restores a verified backup into a **distinct** Compose project, never the live one. It
rebuilds a clean database volume (destroying only that isolated project's volumes),
bootstraps roles from the protected secret files, streams each decrypted dump straight
into `pg_restore`, and verifies the restored application schema, catalogue tables and
Keycloak realm/account counts. It requires the private key in `--gnupg-home` and explicit
destructive confirmation.

```bash
deploy/private-dev/bin/restore-private-dev \
  --backup /mnt/vgp-backups/<backup-id> \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --gnupg-home /etc/videogame-platform/dev/restore-gnupg \
  --isolated-project vgp-restore-rehearsal \
  --confirm-destroy-isolated-target \
  --evidence-directory /var/lib/videogame-platform/dev/deployment-evidence
```

The isolated restore proves that the encrypted backup can reconstruct the application and Keycloak databases without
touching the live private-dev stack. It does not run the normal deployment smoke because `deploy-private-dev` is
deliberately restricted to the real `vgpdev` deployment boundary.

Full readiness and skeleton-smoke recovery evidence is produced later during the host-loss recovery exercise on a
compatible replacement Linux host. After the isolated restore has been verified, tear the rehearsal project down with
`docker compose ... --project-name vgp-restore-rehearsal down --volumes`.

### Rollback versus forward fix

Before recovering a bad deployment, decide whether an older application image is still
schema-compatible. The assessment compares the currently applied Flyway version with the
rollback candidate's packaged migration version and enforces the invariant that an
applied migration is never reverted. When the database is ahead of the candidate and the
intervening migrations are not confirmed expand-only, it recommends a forward fix.

```bash
deploy/private-dev/bin/assess-recovery-strategy \
  --env-file /etc/videogame-platform/dev/runtime.env \
  --target-migration-version <candidate-image-flyway-version> \
  --target-image ghcr.io/rubhern/videogame-platform@sha256:<digest>
```

A `ROLLBACK` decision redeploys the older digest with the normal deployment command and
does not run the older image's migrations. A `FORWARD_FIX` decision means building and
deploying a new corrected revision instead.

### Host-loss recovery

Recovery does not depend on the original hardware; any compatible Linux host is
sufficient. The flow composes existing owned steps:

1. Rebuild the host foundation and runtime per the platform design's *Host foundation
   reconstruction* and the *One-time private host preparation* section above.
2. Restore the latest verified backup with `restore-private-dev` into the live project by
   naming it as the isolated project on the fresh host (there is no other stack to
   protect), or into a rehearsal project first to validate the backup.
3. Deploy the last-good image digest with `deploy-private-dev` and confirm the skeleton
   smoke and readiness checks pass.
4. Record the recovery decision, backup id, image digest, migration version and outcomes
   from the generated evidence records.

The repository provides and rehearses every step's mechanics but cannot itself prove
a physical host rebuild. The accepted evidence boundary for #44 (restore and runtime
proven on a compatible Ubuntu 24.04 environment; physical rebuild, boot-time systemd,
SSH/Tailscale/Serve and the official smoke not re-run) is recorded in the
[operations runbook](../../docs/development/operations-runbook.md#host-loss-recovery).
