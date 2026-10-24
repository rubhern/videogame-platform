# Public-production runtime configuration

- **Status:** Prepared runtime, manual deployment and recovery candidate (#275), pending real-host validation; never deployed

This directory holds the versioned runtime configuration for the future public
production host. No host, DNS record, public certificate or telemetry stack is operated for it.
Manual deployment and backup/recovery commands operate only within isolated synthetic
rehearsals; real-host deployment remains blocked. [ADR-0025](../../docs/decisions/0025-host-public-production-on-a-deferred-single-vps.md)
accepts the single-VPS design, but the [public-production gates](../../docs/development/delivery-lifecycle.md#public-production-gates)
remain open; nothing here authorizes a purchase, an exposure or a change to private-dev.

The [platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns the topology, trust boundary, resource envelope and recovery policy. This page owns
the secret inventory, the manual rotation and revocation model, and the boundary between
what the repository checks and what only a real host can prove.

| File | Owns |
|---|---|
| [`compose.yaml`](compose.yaml) | Services, networks, published ports, images, secrets, limits |
| [`caddy/Caddyfile`](caddy/Caddyfile) | HTTPS edge: hosts, routes, forwarding headers, bounds, request limits, maintenance gate |
| [`caddy/Dockerfile`](caddy/Dockerfile) | Edge image: stock Caddy plus the [ADR-0030](../../docs/decisions/0030-limit-public-edge-requests-with-a-caddy-module.md) limiter module, pinned |
| [`bin/run-postgres`](bin/run-postgres) | Reads role secret files before the stock entrypoint drops supplemental groups |
| [`bin/run-keycloak`](bin/run-keycloak) | Keycloak entrypoint that reads secret files and refuses empty inputs |
| [`../../scripts/public_production_deployment.py`](../../scripts/public_production_deployment.py) | Manual deployment preflight, lock, migration, activation, smoke and receipts; rehearsal only |
| [`bin/run-application`](bin/run-application), [`bin/run-migrations`](bin/run-migrations) | Fail-closed recovery authorization before the shared secret wrappers |
| [`bin/recovery`](bin/recovery) | Paired backup, bounded copy/retention, isolated restore and deletion/readiness gates |
| [`bin/recovery-personal-data`](bin/recovery-personal-data) | Private transport for existing privacy commands: fenced live cleanup or closed-consumer restore replay |
| [`bin/identity-maintenance`](bin/identity-maintenance) | Identity deny/drain fence for the privacy cleanup, on the recovery gate and lease |
| [`runtime.env.example`](runtime.env.example) | Every non-secret host input, with placeholders |
| [`keycloak/`](keycloak/) | Production realm import and Keycloak abuse bounds, owned by #274 |

The application and migration actor reuse the environment-neutral private-dev secret
wrappers and the shared PostgreSQL bootstrap read-only; production changes neither.

## Runtime inputs

Copy `runtime.env.example` to the protected production configuration directory and
replace its placeholders. The approved names and origins are fixed in Compose and the
Caddyfile, not in this file. Every image is a `repository@sha256:` digest that trusted
`main` published, taken from that run's publication records; the
[delivery contract](../../docs/architecture/deployment/mvp-platform-and-delivery.md#immutable-deployment-and-failure)
names the repositories. The contract test refuses any other repository, a tag, a
placeholder, or a migration digest that differs from the application's.

## Before the first production start

These hold for the first production start; the disposable exercise below verifies the local sequence:

- **Migrations before the application.** The application never migrates at startup and
  sits behind the `application` profile; the one-shot `migration` actor sits behind
  `deployment` and runs the same digest. A deployment starts PostgreSQL, runs
  `migration` to successful completion, and only then starts the application; a failed
  migration leaves the previous application running or stopped, never activated.
- **Keycloak memory.** The 768 MiB ceiling remains an envelope hypothesis. The local
  exercise measures realm import and synthetic identity journeys within that ceiling;
  real-host load and capacity qualification remain necessary.
- **Registry access.** Newly published GHCR packages are private until the owner
  changes their visibility, so the host can pull the dependency images only after that
  step or with a read-only registry credential, which would be a new secret.

## Manual deployment procedure

**Implemented for isolated synthetic rehearsal; not exercised on a real host.**
The [deployment command](../../scripts/public_production_deployment.py) reuses the
trusted-main verifier and the integration harness below. It accepts an exact source
revision, the application's publication digest, an owner-reviewed runtime-source
fingerprint and a new evidence directory. It never selects a different revision or
falls back to a tag, failed run, expired publication or locally built image.

1. Review the runtime sources and print their fingerprint:
   `python3 scripts/public_production_deployment.py --print-configuration-fingerprint`.
2. Obtain the full main SHA and application digest from that successful run's retained
   publication record. Review its quality/security and inherited dependency findings.
3. Run the manual rehearsal from the repository root:

   ```bash
   python3 scripts/public_production_deployment.py --rehearsal \
     --revision <full-trusted-main-source-sha> \
     --digest sha256:<application-publication-digest> \
     --configuration-fingerprint <reviewed-fingerprint> \
     --evidence <new-local-evidence-directory> \
     --model-dir <existing-approved-tcbig-directory>
   ```

4. Inspect the sanitized integration receipt and each `deployment-*.json` receipt.
   A complete pass includes stage E, teardown and the state-preservation assertion;
   an omitted model remains explicitly unexercised. Retain only these sanitized outputs.

The source fingerprint includes runtime configuration, mounted wrappers/bootstrap and
model policy, using actual file bytes and executable modes. The same inventory also
covers imported privacy/ledger/archive/admin code, backup-common, Grafana reader SQL,
mounted dashboards/datasources, startup/notification scripts and alert templates,
and the deployment verifier and its trust client. It also includes
`scripts/provision-grafana-reader.sh`, its production validation prerequisite
`scripts/test-public-production-runtime.py` and that validator's edge probe.
The deployment tests independently discover repository bind sources from rendered
base/monitoring Compose, operator-command entrypoints in this runbook and their
static executable/imported file dependencies. New reachable files outside the
inventory fail that completeness check, including untracked executables. Local-only
provisioning branches and rehearsal-only runbook commands are outside the runtime
inventory; their own evidence records source hashes. This is a bounded audit of the
repository's static path/import conventions, not a parser for arbitrary generated
commands. Review new dynamic loading explicitly. Symlinked directories and files
are refused instead of skipped. Mounted directories include newly
added files; secrets and approved external model bytes retain their separate input checks. It must match the selected
image's trusted source inventory. The rendered synthetic configuration has a separate
fingerprint, checked before every attempt. A different runtime configuration requires
its own reviewed rollout; this command replaces only the application. Dependency
images must already be running at the approved identities and ready. Synthetic secret
file checks, TLS verification, production Compose validation and egress isolation
remain enforced. Remote Docker contexts are refused; the rehearsal requires a local
Docker Unix socket.

Each attempt takes a nonblocking exclusive lock for that disposable runtime before
reading state, then acquires the existing recovery actor lease and checks the recovery
startup gate. A closed gate or concurrent backup/recovery refuses deployment. An unrecorded application, interrupted/incomplete state, missing
secret/dependency, image mismatch or changed configuration refuses before mutation.
After preflight it records the attempt, stops the old application, runs the dedicated
Flyway actor from the target digest, then activates and waits for the candidate.
The smoke verifies running source/version metadata, the HTTPS shell, one bounded
anonymous catalogue read (including the approved empty-catalogue outcome) and denied
management ingress. It creates no accounts and needs no private-dev smoke credentials.

| Failure | Operator response |
|---|---|
| Trust, fingerprint, inputs, dependency or state refusal | Correct the reviewed input/state; no activation or migration was attempted |
| Concurrent attempt | Wait for the current attempt, inspect its receipt, then deliberately retry |
| Failed migration | Candidate never activates; application stays stopped. Assess partial DDL and use a forward fix or separately approved recovery |
| Failed readiness or smoke | Deployment fails and the candidate is stopped; inspect the phase and fix forward |
| Failure to stop the candidate | Receipt requires private operator action; do not treat the application as safely stopped |
| Interrupted receipt/state write | Assess the runtime and schema before reconstructing state; never delete a lock/state file to force promotion |
| Older source / rollback | Refused without an explicit rollback request and demonstrated compatibility; never revert Flyway |

The internal transition procedure permits an explicitly requested older trusted image
only when its complete migration-source inventory matches the previous successful
release, the live Flyway history matches that receipt, and the same-image Flyway
validation leaves history unchanged. This deliberately excludes rollback across any
migration change; equal application versions or a remembered digest prove nothing.
The rehearsal command does not offer a real-host rollback switch. Other recovery
needs a forward fix or separately approved recovery, with the edge closed when
security or data integrity is uncertain.

Receipts identify source/run/attempt/security evidence, image digest, application and
migration versions, runtime/rendered configuration fingerprints, previous installed
version/image, outcome, phase and a bounded failure reason. Raw actor/browser output,
secrets, account information and tokens are withheld. A failed/concurrent attempt
gets its own receipt and cannot overwrite another attempt's successful state.

**Real-host refusal is unconditional in this increment.** There is no approval-file
or environment-variable override. Real-host proof of the prepared paired encrypted backup,
restore/reconstruction and deletion replay procedures, release/provider/privacy acceptance,
private host administration and the applicable [purchase/exposure prerequisites](../../docs/development/delivery-lifecycle.md#public-production-gates)
remain necessary before a separately reviewed host adapter can be enabled. A local
pass authorizes no VPS purchase, DNS change, public ingress or private-dev operation.

## Secret inventory

Every secret is one file in the directory named by `PRODUCTION_SECRETS_DIR`, outside
Git and every checkout and independent of local, CI and private-dev secrets. Compose
mounts each file only into its consumers below `/run/secrets`; entrypoints read it into
the process environment, never into Compose metadata, command arguments or logs. This
is file delivery on one host, not an encrypted vault.

| File | Purpose | Consumers | Effective after first start |
|---|---|---|---|
| `postgres-admin-password` | PostgreSQL superuser, break-glass only | `postgres` | Stored in the cluster at initialization |
| `application-db-password` | Runtime role `videogame_app`, no DDL | `postgres` (initialization), `application` | Role password plus file |
| `application-migration-db-password` | Migration role `videogame_app_migrator` | `postgres` (initialization), `migration` | Role password plus file |
| `keycloak-db-password` | Identity role `videogame_keycloak` | `postgres` (initialization), `keycloak` | Role password plus file |
| `keycloak-admin-password` | Temporary master-realm bootstrap administrator | `keycloak` | Used only while no administrator exists |
| `keycloak-bff-client-secret` | Confidential BFF client credential | `keycloak` (realm import), `application` | Client secret in Keycloak plus file |
| `keycloak-smtp-username`, `keycloak-smtp-password` | Transactional relay credentials | `keycloak` (realm import) | Stored in the realm, inside the identity database |
| `igdb-client-id`, `igdb-client-secret` | Provider credentials for bounded synchronization | `application` | File only; may stay empty while synchronization is off |

Storage requirements, verified on the host before first start:

- The directory belongs to `root`, group `PRODUCTION_SECRETS_GID`, mode `0750`; each
  file mode `0640`. Only the consuming containers join that group. Nothing else on the
  host reads it, and it is excluded from any repository, image or CI artefact.
- Values are generated on a trusted owner machine, never by CI or an agent, and never
  pasted into a shell history, issue or log.
- Reconstruction material (every file plus the Keycloak administrator's credentials)
  is kept encrypted, off the host and off the provider account, separately from the
  backup decryption key. The [recovery policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
  owns where; the isolated recovery exercise below checks reconstruction with synthetic inputs.

## Rotation and revocation (manual)

Replacing a file never rotates a credential that the owning service already stores.
Rotation is one reviewed owner operation over private administration, in this order:
change the credential where it is enforced, replace the file, recreate only its
consumers, then confirm readiness. No automation exists.

| Credential | Where it is enforced | Consequence of the operation |
|---|---|---|
| Database role passwords | `ALTER ROLE … PASSWORD` through `docker compose exec postgres` | Recreate the consumer; the migration actor reads its file on each run |
| BFF client secret | Regenerate in the realm through the Admin API from inside the container | Sign-in fails until the application is recreated with the new file |
| Relay credentials | New key at the relay provider, then the realm's SMTP settings | Revoke the old key at the provider after a verified send |
| Provider credentials | The provider's developer console | Recreate the application |
| Bootstrap administrator | After first start, create a named administrator with MFA and delete the bootstrap user | The file then has no effect |

On suspected compromise, revoke at the source first (provider key, client secret, role
password), close the public edge if data or identity integrity is in doubt, then
rotate as above. Never rotate by deleting the identity database or re-importing the
realm over existing accounts.

## Validation

```bash
python3 -I scripts/test-public-production-runtime.py
```

It renders Compose with synthetic inputs, checks the exposure, isolation, image,
secret and resource contract, builds a disposable edge image from the recipe (never
published), adapts the Caddyfile, exercises the real routes, forwarding-header
handling, request limits and maintenance gate with a disposable internal CA and
upstream stand-ins on unpublished networks, and proves that each deliberate violation
is detected.
`--env-file <protected runtime.env>` runs only the static Compose checks against a
real host's inputs, refusing any remaining placeholder, without reading secret files
or starting containers.

That check is configuration evidence. The separate exercise below runs the published
runtime; neither proves real-host behaviour.

## Disposable integration exercise

```bash
python3 scripts/test-public-production-integration.py
python3 scripts/public_production_integration.py \
  --revision <full-trusted-main-source-sha> \
  --evidence <new-local-evidence-directory> \
  --model-dir <existing-approved-tcbig-directory>
```

Install repository npm dependencies first (`npm ci --ignore-scripts`). The exercise
requires Docker Compose, OpenSSL, authenticated `gh` evidence access and Docker pull
access to the packages. It reuses the private-dev promotion trust verifier, publication
records, issuer checker, production realm and production identity browser journey.
The browser tooling obtains NSS utilities in a disposable container before isolation;
no host package, certificate store or name-resolution file is changed.

`--stage A`, `B`, `C` or `D` stops after that stage; the default `E` runs all stages. Every
invocation creates fresh project-scoped volumes and networks, preserves their synthetic
state between deployment attempts within that invocation, verifies migrations
before application startup, and tears down all profiles, including on failure or
interruption. An occupied production trust subnet is a refusal, never permission to
alter an existing network. Run this exercise and the edge contract test sequentially:
they deliberately use the same fixed proxy trust addresses.

Only a temporary rendering of the production Compose configuration is changed:
all host ports are removed, all networks have external egress disabled, and a private
test-client network reaches Caddy under the canonical production names. A disposable
CA is trusted by the browser, BFF and identity JVM; certificate verification stays
enabled. Authenticated STARTTLS Mailpit replaces the relay. The existing bounded
prototype fixture is inserted only after production-only Flyway completes. No
production image is built, no mutable tag substitutes for a missing publication, and
no real provider or mail service is reachable. The production secret permissions and
service ceilings remain in force. PostgreSQL's wrapper reads role files before
`gosu` discards supplemental groups; the shared bootstrap consumes their environment
form without changing private-dev.

Omit `--model-dir` only when the approved artifact is unavailable: the receipt then
explicitly records the localizer as **not exercised**. The harness never downloads,
converts or substitutes a model. A supplied wrong, incomplete or corrupt model is a
failure. A passing partial-stage receipt is not a complete integration result.

Stage B activates through the manual deployment procedure. Stage E repeats successful
attempts and injects explicitly recorded missing-migration-database, stopped-readiness and smoke
failures, then retries while checking a synthetic catalogue row is unchanged. No failure
injection is available outside the isolated rehearsal. Focused offline tests also cover
provenance, concurrent attempts and incompatible rollback; those refusals do not need
real registry/runtime failures.

For a focused deployment/localizer experiment, `--operations-only` explicitly omits
stage C and records `browser: not-exercised-operations-only`; it still performs the
trusted-source/configuration preflight and every selected operational check. Such a
run is never complete candidate acceptance or a substitute for the browser journey.

The evidence directory contains a sanitized JSON receipt: source/run/attempt/security
provenance, all immutable image references, stages, migration version, configuration
fingerprint, sampled peak/final idle working-set memory, restart/OOM observations and
teardown result. Raw browser reports, account data, tokens, mail, secrets and service
logs are not exported. Short-lived actors can finish between samples; an unobserved
peak is `null`, never zero. Samples can miss short peaks; neither missing observations nor
a successful local workload establishes VPS headroom. The focused refusal/isolation
tests run in selective runtime CI. The full exercise stays local because it needs
retained trusted-main publications, registry access and an explicitly supplied model;
it must not build substitute production images to make a pull-request gate pass.

## Not yet validated

These need later increments or the real host (#276):

- Public ACME issuance and renewal, DNS, IPv4 and IPv6 exposure, host firewall and
  Docker NAT. Docker's userland proxy presents IPv6 clients with an internal source
  address, which matters before any per-source limit or diagnostic relies on it.
- Real-host startup with the public certificate and protected host secret files,
  published digest pulls, calibrated representative load and every resource ceiling;
  local receipts cover only the disposable machine and supplied model.
- Real-host deployment/migration/readiness/smoke and recovery,
  telemetry, the owner's Keycloak administration path, and the
  [canonical issuer check](../../docs/architecture/deployment/mvp-platform-and-delivery.md#canonical-issuer-contract)
  with the publicly served production certificate.
- The [request limits and maintenance gate](#request-limits-on-the-real-host), whose
  thresholds, like the edge's body, header and timeout bounds, are uncalibrated.

## Paired backup and recovery candidate

**Implemented for isolated synthetic rehearsal (#275 increment 5); not operated on
production or Lenovo.** [`bin/recovery`](bin/recovery) owns the mechanics. The
[platform recovery policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns destinations, the 35-day ceiling and the proposed recovery objectives.
`bin/recovery-personal-data` adapts the existing privacy commands and deletion-ledger
driver to private production administration; it does not create a legal archive.

A new `recovery-state` Compose volume starts empty. The application and migration
wrappers refuse startup unless it contains `READY` without `PENDING.json`. Only the
owner recovery actor writes that volume. Its private `--state` directory is an
**owner-side journal**, outside Git, separate from the runtime volume. Preserve it
through an interrupted operation. Creating `READY` by hand bypasses the control and
is not a recovery procedure. The future production promotion command must share
this actor's runtime-volume lease and respect the startup guard; no production promotion command
exists in this revision.

Before first boot, the owner stages the trusted checkout, protected configuration,
secret files and immutable-image provenance, validates the runtime inputs, and renders
a protected Compose JSON file for the intended target. It contains file references,
never secret values. With a fresh project and no database or certificate volume,
`initialize` authorizes the existing migration-first startup sequence. It refuses
existing durable volumes/containers; it is never a shortcut around restoration.
Use a new 0700 journal directory owned by the invoking operator. Keep the rendered
configuration and the journal in protected owner administration storage.

The following are command shapes, **not authorization to connect to a host**. Every
command requires a new `--receipt` path; receipts contain outcomes, opaque backup IDs,
checksums, schema/image identities, sizes and durations, never account identifiers,
passwords, SMTP settings, database contents or raw diagnostics.

```bash
recovery=deploy/public-production/bin/recovery
python3 "$recovery" initialize --config /protected/compose.json \
  --project gameometro-production --state /protected/recovery-journal \
  --receipt /protected/receipts/initialize.json
python3 "$recovery" backup --config /protected/compose.json \
  --project gameometro-production --state /protected/recovery-journal \
  --keyring /protected/public-keyring --recipient OWNER_40_HEX_FINGERPRINT \
  --destination /protected/backups/NEW_PAIR --receipt /protected/receipts/backup.json
python3 "$recovery" verify --backup /protected/backups/NEW_PAIR \
  --receipt /protected/receipts/integrity.json
```

The keyring used by `backup` must contain only the owner's public encryption key;
secret keys and ambiguous recipients are refused. The actor closes the persistent
startup gate, sets the three application/identity roles and the optional provisioned
`videogame_grafana` reader to `NOLOGIN`, stops the edge, application, migration,
Keycloak and Grafana when configured, terminates and waits for existing database
sessions to roll back, then refuses unknown login roles, remaining connections,
prepared transactions or subscriptions. A runtime-volume lease excludes concurrent recovery actors even from different
owner journals or machines, and every actor refuses while an
[identity-maintenance](#identity-maintenance) fence is held. Privileged PostgreSQL/host administration
must be exclusive during this maintenance window; no mechanism protects against a
concurrent root/superuser deliberately bypassing it. Scheduled sync is already off.
There is no claim that sequential dumps share a transaction: **both databases remain
unchanged throughout both dumps**, with a recorded common recovery point. A final
drain check is required before publication.

Use synchronized owner/target clocks; future or expired deletion evidence is a refusal.
Plaintext custom-format dumps and identity comparison scratch files live only in a
0700 directory on owner-side `/dev/shm`, removed on handled errors/interruption.
Provision sufficient bounded tmpfs space and disable swap or use independently
encrypted swap; tmpfs alone does not prevent swapping. An abrupt power loss needs no
plaintext disk recovery. After SIGKILL, inspect/remove abandoned private scratch and
`.partial` ciphertext directories only after proving no actor is active. Never turn
an incomplete directory into a published pair. Failure leaves the journal, gate and
role fences closed. Completed backups also stay closed until explicit `thaw`; it
refuses an interrupted backup. Inspect the journal and service/role state privately
before recovering an incomplete capture; make a new pair rather than guessing that
it succeeded. `thaw` removes only a completed backup fence and starts no services:

```bash
python3 "$recovery" thaw --config /protected/compose.json \
  --project gameometro-production --state /protected/recovery-journal \
  --receipt /protected/receipts/thaw.json
```

After a successful `thaw` or evidence-checked `reopen`, explicitly restart the
approved consumers through the reviewed host procedure, including Grafana when the
overlay is configured. Neither command restarts it automatically. Confirm reader
connectivity and monitoring recovery before ending maintenance.

### Independently secured copy and retention

Run `pull` **on the independent destination under its backup administration account**.
Use an owner-controlled 0600 SSH config, pinned source host key, a dedicated read-only
source credential and no agent forwarding. The source has no credential able to
write, prune or read the destination. `--ssh-host` is an alias in that config; configure
its `IdentityFile`, `IdentitiesOnly yes` and `UserKnownHostsFile` explicitly. Limit the
source account/authorized key to reading the approved ciphertext directory and no
Docker, sudo, shell forwarding or secret access. The destination must not expose
write/delete access to runtime identities or ordinary development processes.

```bash
python3 "$recovery" pull --ssh-config /protected/backup-source.ssh \
  --ssh-host production-backup-reader --remote-backup /protected/backups/NEW_PAIR \
  --destination /protected/independent-backups/NEW_PAIR \
  --max-copy-bytes OWNER_APPROVED_CAPACITY_BYTES \
  --receipt /protected/receipts/copy.json
```

The receiver enforces host-key checking, bounded transfer time/bytes, an exact fixed
regular-file inventory and ciphertext integrity before publishing the destination.
Interrupted copies are removed. `copy` provides the same verified staging sequence
between local directories for rehearsal, with an explicitly local-only receipt.
Neither copy command proves physical independence, access isolation, authenticity of
a compromised source or recoverability. Checksums detect corruption, not malicious
replacement of all files. Protect independently retained manifests and successful
rehearsal receipts against modification; compare them before recovery.

The approved Lenovo destination is **not provisioned or contacted here**. Before
using it, the owner must prove that private-dev, repositories, agents and ordinary
development identities cannot read or modify the store or its administration inputs,
including through root, sudo or Docker. If that privilege isolation cannot be
established, refuse activation and resolve the owner storage decision. Keep rolling
backups, the restricted legal archive, the deletion ledger and their independent
copies in their distinct protected stores; backup pruning must never operate on the
legal archive or ledger.

`retain --backup /protected/independent-backups --verified-receipt
/protected/receipts/recovery-rehearsal.json --receipt NEW_RECEIPT` selects seven
most recent daily representatives plus four weekly representatives within 35 days,
and pins the last completely rehearsed pair by backup ID **and manifest digest**
(at most twelve retained pairs). It refuses corrupt inventories, more than 512 entries,
missing proof and expiry of the last proven pair. Replace and rehearse that pair
before expiry; refusal requires immediate owner action, never indefinite silent
retention beyond the privacy ceiling. Run this independently on every rolling copy.
Monitor incomplete directories separately. Only integral backups count; an integral
pair without a successful full recovery receipt never becomes the last verified
recoverable pair. No recurring job or notification channel is installed here.

### Restoration and deletion gate

Production restoration is **owner-side only**, using an SSH Docker context pointing
at the reconstructed target. The command refuses local/TCP Docker endpoints for the
production project. The private decryption key stays on the owner machine, outside
both runtime hosts, containers, configuration bundles and backup store. Decrypted
bytes stream from owner tmpfs into remote `pg_restore`; the key is never sent.
Prepare the target's protected bind inputs at the paths recorded in the rendered
configuration before using that context. Keep reconstruction secrets encrypted on
independent owner storage separately from the decryption key and encrypted dumps.

Restore the captured trusted image set first; image, PostgreSQL major, Flyway and
Keycloak changelog identities must match. Assess upgrades and forward fixes after a
successful recovery rather than migrating an unverified pair. The shared PostgreSQL
bootstrap reconstructs roles with protected credentials; global roles/password dumps
are excluded. Database host binds, external/unscoped volumes, existing target state
and explicit container names are refused. There is no destructive reset or
`pg_restore --clean` option. Failure after target creation requires deliberate
operator disposal of that exact disposable target before a new restore attempt.

```bash
# Set DOCKER_CONTEXT to the owner's pinned SSH context for the fresh target.
python3 "$recovery" restore --config /protected/rebuilt-compose.json \
  --project gameometro-production --state /protected/rebuilt-journal \
  --backup /protected/independent-backups/NEW_PAIR --keyring /owner/private-keyring \
  --receipt /protected/receipts/restore.json
```

When the pair records `monitoringReader: true`, `restore` also requires
`--grafana-password-file /owner/protected/grafana-password`: an owner-only 0600
reconstruction input, passed to PostgreSQL only over stdin. Its size, UTF-8 content,
nonempty value and absence of embedded line/NUL separators are validated before
any restore resource is created. Retain the same credential as the rendered
Grafana datasource's `grafana_database_password` secret; after explicit reopen and
Grafana restart, verify a query through Grafana's PostgreSQL datasource API.
A direct reader-role SQL login alone does not establish that integration.
Before `pg_restore`, the
actor creates the missing reader as `NOLOGIN`; afterward it reapplies the canonical
reader grants/settings with that credential. The role remains fenced until verified
replay/reopening. `thaw` and `reopen` enable only the known consumer roles, including
that optional reader; unknown login roles still cause refusal. No globals/password dump
is introduced. Legacy pairs without the reader field retain their original contract.

Both decryptions and plaintext checksums pass before creating a database. Restore
checks the schema identities and every rating principal against Keycloak subjects
using the application's exact issuer-derived UUID algorithm. This offline operation
scans both stores, streams identifiers through private scratch files and uses external
sorting with an 8 MiB sort buffer; memory never scales with the number of accounts.
No account IDs enter a receipt. Orphans are a refusal requiring investigation.

Before replay the owner reconciles the **authoritative current ledger and its
independent second copy**, including requests interrupted before their first mutation.
Both must be matching private 0600 files in distinct 0700 owner directories. Supply a
private checkpoint JSON with `environment: "production"`, `ledgerSha256` and an
ISO-8601 UTC `asOf` representing that explicit reconciliation. The checkpoint is a
trusted owner attestation, not evidence that a stale ledger became current merely by
hashing it. It must be no older than one hour and must postdate the backup recovery
point. Missing, changed, divergent or stale evidence refuses reopening, including for
an intentionally empty ledger. Preserve the ledger while any backup can resurrect
its subjects; do not prune it with backups.

Use `replay` with `--ledger`, `--ledger-copy`, `--checkpoint` and `--host-command`.
The host command is the argument-quoted owner-side invocation of
`bin/recovery-personal-data --config … --project … --state … --admin-username …
--admin-password-file …`. Its password file stays on the owner machine; its HTTP
transport joins only the private identity-data network without a published port.
The actor starts only Keycloak, reuses the existing revoke/delete-ratings/
delete-identity/verify sequence and validates its receipt. It never recreates a legal
archive as a substitute for the archive preserved with the request. Incomplete
requests requiring legal sealing retain the existing ledger driver's refusal until
that separate protected archive is reconciled. Forward its complete inputs on the
`recovery replay` invocation: `--archive-root`, `--archive-copy`, `--gnupg-home`,
`--recipient`, `--signer`, `--proof-root` and `--holds`. These are the existing ledger's
protected stores and full key fingerprints; partial sets are refused. `--keyring`
for decrypting the backup and `--gnupg-home` for archive verification/signing have
separate responsibilities. An incomplete profiled replay reuses the original sealed
ciphertext; it must never take a fresh snapshot or replace the retained legal source.
Absent, divergent, held or unusable source leaves `REPLAY`/`READY` unauthorized.

After replay all restored realm sessions must be revoked, including offline sessions.
The recovery adapter uses realm logout followed by bounded discovery and
[consent/offline-token revocation](https://www.keycloak.org/docs-api/26.6.4/rest-api/#_delete_adminrealmsrealmusersuser_idconsentsclient),
then requires both persisted session stores to be empty for the restored realm.
A two-minute revocation budget leaves the gate closed on exhaustion; resume explicitly.

BFF sessions disappear with the stopped process. A failed replay leaves reopening
blocked and is retryable. `reopen` takes the same ledger/checkpoint arguments,
rechecks freshness, matching replay evidence and identity consistency, and rejects
unexpected login roles, prepared transactions and subscriptions before enabling
any consumer. Only PostgreSQL and the replay's Keycloak role may already have
LOGIN at this boundary. It then authorizes
startup without starting services. Use the reviewed migration/readiness/smoke flow
afterwards. A restore receipt alone is never permission to start the application. Once readiness
passes, run `record-recovery` with the same runtime, backup and deletion-evidence
arguments. It binds the observed readiness, replay, startup gate, captured images and
manifest digest into the protected receipt accepted by retention. Keep that receipt
independently with the verified pair; the synthetic rehearsal's receipt cannot pin a
different real backup.

Replay verifies absence without completing an interrupted owner-ledger request.
After recording recovery readiness, finish each incomplete request using the same
protected archive/key/proof/holds inputs and the ledger's `retry` command against
`bin/recovery-personal-data --identity-cleanup`. Supply
`retry --maintenance-command "python3 deploy/public-production/bin/identity-maintenance --config … --project … --state …"`.
The same adapter is supported for `erase --maintenance-command …` when beginning a
live owner-verified erasure. The ledger holds its exclusive lock across maintenance
enter/check, mutation, suppression proof and completion; the production transport
also rechecks the fence under its runtime lease for each mutation. A failed entry,
drain, identity check or conflicting owner refuses mutation; the exit callback is
registered before entry to handle lost acknowledgements. Cleanup retries retain
their additional recorded-cutoff, eligibility and hold checks.

Verify both ledger copies report completion, the suppression proof's
`archiveSha256` matches the original sealed source, and both ciphertext copies
remain unchanged. A completion appends to the ledger, so its digest advances:
retain the pre-retry recovery receipt as point-in-time evidence and reconcile a
fresh checkpoint for later recovery. Never relabel an older replay proof as a
proof of the new ledger digest.

### Local exercise and remaining host checks

```bash
python3 scripts/test-production-recovery.py
python3 scripts/test-production-recovery-lock.py
python3 scripts/rehearse-production-recovery.py \
  --revision FULL_TRUSTED_MAIN_SHA --evidence /new/local/recovery-evidence
```

The complete exercise reuses the immutable-publication verifier, production Compose,
secret wrappers, actual migrations, synthetic identities and existing ledger driver.
It captures/copies an encrypted pair, destroys the original runtime and source copy,
reconstructs roles with new synthetic database credentials and separately retained
configuration/secrets, restores from the simulated independent copy, verifies
identity consistency, refuses early application startup, replays a post-backup
request with only Keycloak running and checks application readiness. It renders
the real monitoring overlay, makes notification egress internal only in the
isolated configuration, proves Grafana stops before session drain, then restarts
Grafana and queries its actual PostgreSQL datasource after reopening. It completes
the interrupted profiled request through fenced live retry, checking replicated
ledger completion, suppression proof and unchanged original ciphertext. All containers,
networks, volumes, keys and plaintext are disposed; only sanitized receipts remain.
Local measurements describe that machine and tiny dataset, not future VPS RPO/RTO.
The full exercise needs trusted publication/registry access and stays local; focused
encryption/refusal tests run in selective CI.

Before public authorization, #276 must prove: actual storage and root/Docker privilege
isolation on Lenovo; physical/account independence and second ledger/archive copies;
pinned read-only transfer credentials, connectivity, capacity, interruption handling
and destination integrity checks; encrypted/disabled swap and plaintext scratch limits;
protected secrets/config restoration and private-key custody; representative backup
size, temporary space, transfer and recovery duration; a fresh-host reconstruction
over the intended SSH context; real owner identity administration, offline-token
revocation, deletion replay and smoke; every copy's 35-day expiry and replacement of
the last verified pair; scheduling/failure-age notification through a tested private
channel. RPO <=24h and RTO <=48h remain proposals until measured and accepted there.

## Edge limits and identity maintenance

**Implemented for isolated synthetic exercise (#275 increment 6); not operated on a
host.** The [platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#public-edge-request-limits)
owns the limit classes, source identity, refusal semantics and calibration status;
the Caddyfile owns every threshold. The edge reads the `recovery-state` gate
read-only, so a closed or interrupted recovery is a 503 maintenance response on both
hosts even if the edge is started during it. Recovery commands still stop the edge.

### Maintenance modes

Two modes share the `recovery-state` volume and the recovery actor's lease; each
refuses to start while the other is active, so they never overlap.

| | Identity-only fence | Global write freeze |
|---|---|---|
| Purpose | Unverified-account cleanup and its retry | Paired backup, restore, deletion replay and reopening |
| Owner | `bin/identity-maintenance` (`IDENTITY-MAINTENANCE.json`) | `bin/recovery` (no `READY`, or `PENDING.json`) |
| Public edge | Identity host 503, BFF issuer calls included; product host unchanged | Both hosts 503, then the edge is stopped |
| Writers | Keycloak keeps running and serving private Admin calls; drain proved by its in-flight count | Application, migration, edge and Keycloak stopped; database roles `NOLOGIN`, sessions terminated |
| Startup | Unchanged | Application and migration wrappers refuse without `READY` |
| Release | `exit` by the same run after a second drain | `thaw` or `reopen` with their evidence; services started separately |

Neither mode has an alternate public route. Only the edge publishes ports; it routes no
Keycloak administration, management or Actuator path, and refuses non-canonical identity
paths that Keycloak would otherwise route to a limited endpoint. The application calls
Keycloak only through the edge, as an OIDC client, never its Admin API. The remaining
writer is the owner's private administration, which both modes deliberately keep and
which no mechanism here can stop a root or superuser from bypassing.

### Identity maintenance

[`bin/identity-maintenance`](bin/identity-maintenance) is the deny/drain adapter that
`personal-data-ledger cleanup-unverified --execute --maintenance-command` and cleanup
`retry` require. It takes the recovery actor's runtime lease for each call:

```bash
python3 deploy/public-production/bin/identity-maintenance --config /protected/compose.json \
  --project gameometro-production --state /protected/recovery-journal enter --run-id <UUID>
```

The ledger appends `enter`, `check` or `exit` and `--run-id`, and accepts only its JSON
answer. `enter` refuses unless the recovery gate is open and no other run holds the
fence, writes `IDENTITY-MAINTENANCE.json` naming the run, confirms that the running edge
sees it, waits beyond the edge's upstream dial bound and then until Keycloak reports
no in-flight public request (`http_server_active_requests` on its public listener,
read on the management interface). A stopped Keycloak has nothing in flight; a missing
or ambiguous count is a refusal. `check` repeats the ownership, edge and drain proof.
`exit` waits for the same drain, so an administrative mutation that outlived its
client settles first, and then removes only its own fence; a repeated or enter-less
exit is harmless. The fence survives failed calls, adapter interruption and edge
restarts, and identity stays closed until its run exits.

While the fence exists every identity-host request, including the BFF's issuer calls,
answers 503 maintenance; catalogue reads, product writes and existing BFF sessions
continue. Backup, restore, thaw, replay, reopening and deployment all refuse until
it is released. A failed `exit` is an owner incident: inspect the run and Keycloak
privately, then repeat `exit` with the same run ID. Never delete the file by hand to
reopen identity; that bypasses the drain proof.

The prepared production transport is `bin/recovery-personal-data --identity-cleanup`.
It reuses the existing privacy operations, ledger, protected archive and suppression
proof. It acquires the recovery lease for each call, requires open recovery
authorization and an identifiable application container, and refuses mutations
without rechecking the identity fence and drain under that lease. Candidate reads
retain the native server filter and 100-account bound. Omit `--identity-cleanup`
for restore replay: its parent recovery actor owns the lease and all consumers must
remain closed. Never use live cleanup mode as a restore adapter.

Command shapes for protected owner administration, **not permission to operate a host**:

```bash
python3 deploy/private-dev/bin/personal-data-ledger \
  --ledger /protected/ledger.jsonl --ledger-copy /independent/ledger.jsonl \
  --host-command "python3 deploy/public-production/bin/recovery-personal-data --config /protected/compose.json --project gameometro-production --state /protected/recovery-journal --admin-username OPERATOR --admin-password-file /protected/admin-password --identity-cleanup" \
  cleanup-unverified
```

The default is a read-only probe. For execution add the protected archive/key/proof/
holds options from the [privacy procedure](../../docs/development/operations-runbook.md#privacy-phase-2-administration)
and append `--execute --maintenance-command "python3 deploy/public-production/bin/identity-maintenance --config /protected/compose.json --project gameometro-production --state /protected/recovery-journal"`.
Use the same global inputs and maintenance command for `retry`; inspect ledger status
before admitting a new cleanup. Keep keys and both archive/ledger copies in their
approved independent protection domains. No timer is installed. Actual host drain,
backlog/cadence, archive access and second-copy independence remain #276 gates.

Reproduce the synthetic transport exercise with
`python3 scripts/rehearse-production-cleanup.py --revision FULL_TRUSTED_MAIN_SHA --evidence NEW_DIRECTORY --browser --model-dir EXISTING_APPROVED_MODEL`.
Its receipt distinguishes the locally modified transport/configuration fingerprint
from the trusted-main application and dependency images. This is working-tree
integration evidence, never permission to promote an unpublished configuration.

### Limiter memory

```bash
python3 scripts/measure-edge-limiter-memory.py --family 6 --sources 8000 --rate 500 \
  --evidence <new-local-evidence-directory>
```

The measurement runs the reviewed Caddyfile on an edge image built from the recipe,
with the production memory, CPU and PID ceilings, and drives distinct IPv4 addresses or
IPv6 /64s from one disposable client: each source opens TLS once per host and touches
every per-source zone (`--zones all`) or only catalogue reads (`--zones reads`). It
samples the edge's cgroup memory during churn and through the sweep of expired limiters,
then requires a fresh source to be served. It needs Docker only and stays local: it is a
measurement, not a pass/fail gate.

Local results on the reviewed configuration (2026-10-21, one workstation, synthetic):

| Churn | New sources/s reached | Peak | End of churn | After 2–3 min | Edge |
|---|---|---|---|---|---|
| 8,000 IPv6 /64s, every zone | 37 | 82 MiB | 59 MiB | 34 MiB | No OOM or restart; fresh source served |
| 20,000 IPv4, catalogue reads | 32 | 107 MiB | 40 MiB | 39 MiB | No OOM or restart; fresh source served |

The edge's 0.5 CPU ceiling, spent on TLS handshakes, set the arrival rate; the requested
rate was never reached, and many refused or timed-out requests show that legitimate
users degrade under such churn before memory runs out. Peaks include connection state
and stay under the Go runtime's soft limit derived from the cgroup ceiling. Before the
long windows were rescaled, the same IPv6 run peaked at 114 MiB. These are local
observations, not a guarantee: a client that reuses TLS sessions, more distinct sources or
a faster host can still reach the 128 MiB ceiling, which restarts the edge and resets
every quota.

### Request limits on the real host

Local and CI evidence covers the Caddy configuration, the rebuilt binary and synthetic
clients on Docker bridge networks. #276 must establish, before public registration
opens:

- **Source addresses.** External IPv4 and IPv6 clients reach the edge with their own
  addresses through Docker's published ports. If Docker's userland proxy hides IPv6
  clients behind a network gateway, the edge aborts them: enable IPv6 on the edge
  networks or disable the userland proxy, then verify again. Host-originated requests
  through the proxy are refused by design.
- **Thresholds.** Real browser journeys, including mobile carrier NAT, shared IPv4 and
  IPv6 /64 households, sign-in, registration, verification and recovery, are not
  refused; aggregate budgets are set from measured VPS headroom.
- **Edge resources.** Repeat the limiter-memory measurement on the VPS's CPU, including
  TLS session resumption, and decide whether the 128 MiB ceiling, a restart's quota reset
  and the CPU-bound degradation under churn are acceptable; do not raise the ceiling
  without that evidence.
- **BFF calls.** The application's fixed address is the only peer whose issuer calls
  use their budget, with the real canonical certificate.
- **Maintenance.** A real cleanup window denies the identity host, drains in-flight
  verification requests, settles administrative mutations and reopens; record its
  duration and frequency.
- **Mail.** All verification and recovery sends stay within the relay allowance; the
  minute limits do not provide a daily reserve.
- **Signals.** Refusal counts need the increment-7
  [edge HTTP metrics](../../docs/development/observability.md#identity-and-overload-security-signals)
  with their actual scrape and notification; none is collected now.


## Production observability candidate

**Prepared and tested only in disposable local infrastructure.** Use the base runtime
and `compose.observability.yaml` together when rendering the production candidate.
[Observability](../../docs/development/observability.md#public-production-observability-proposal)
owns privacy, signal interpretation, calibration and resource/storage budgets;
[production monitoring](../../docs/development/operations-runbook.md#production-monitoring)
owns the incident response. No production or private-dev operation was performed.

```bash
docker compose --env-file /protected/production/runtime.env \
  --file deploy/public-production/compose.yaml \
  --file deploy/public-production/compose.observability.yaml \
  --profile application config --quiet
bash scripts/provision-grafana-reader.sh --production /protected/production/runtime.env
```

The reader command is a **host operation after migrations**, never part of the agent's
local proof. It provisions the existing view-only role with the production database
secret. The base-only runtime remains usable for the earlier deployment/recovery
exercises with telemetry export off; production acceptance requires the combined
candidate. Include the overlay in the reviewed rendered configuration/fingerprint.
Never silently reuse an earlier base-only fingerprint as telemetry acceptance.

Additional protected production files, with the same inventory/storage rules above:

| File | Consumer and rotation |
|---|---|
| `grafana-admin-password` | Production Grafana bootstrap; rotate the persisted administrator credential through private administration as well as replacing the file |
| `grafana-database-password` | Production Grafana and the host reader-provisioning command; update the PostgreSQL role, replace the file and recreate Grafana together |
| `grafana-telegram-bot-token`, `grafana-telegram-chat-id` | Production Grafana only; independent production bot/destination, never private-dev values. Replace files and recreate Grafana; revoke the former bot token |

Files must exist even while delivery is off; empty Telegram placeholders are accepted
only with `PRODUCTION_TELEGRAM_ENABLED=false`. Enabling delivery validates the
protected values and fails closed on invalid input. Do not enable product notices.
The generated notification configuration lives in a protected tmpfs. Its internal
contact-point names are inherited compatibility identifiers, not a connection to
private-dev. Grafana, Prometheus, exporters and Collector publish no ports. Use the
existing private SSH boundary and a local tunnel to the Grafana container address;
re-resolve that address after recreation. Keep the tunnel local and authenticate.

Prepare an owner-controlled `PRODUCTION_METRICS_DIR` readable by the unprivileged
node exporter, containing only sanitized `.prom` files. The proposed single filesystem
must include Docker/data volumes; additional filesystems need an explicit bounded
collector change before acceptance. The standard exporter mounts host proc/sys/root
read-only without secrets-group membership or extra privileges. The systemd unit/timer
under `observability/systemd/` are **templates, not installed**. Review the absolute
checkout path and evidence paths before installation. The host timer reads only
selected Docker state and the bounded safe Caddy window, then atomically replaces
one textfile. Failed sampling leaves its timestamp stale. Test cgroup v2, permissions,
PID mapping, recreation, timer delays and the fixed root mount on the actual VPS;
Docker Desktop's non-shared root uses a test-only mount-propagation seam.

The timer expects three independent protected inputs under the observability directory:

- `backup.json`: the latest sanitized receipt from the existing paired-backup actor,
  including failed attempts. Publish it atomically after every attempt; do not retain
  only successes. Missing/corrupt input alerts.
- `offhost.json`: a monitoring attestation generated **on the independent destination**
  by `observability/verify-offhost.py`. Supply its verified encrypted pair directory,
  successful `pull` receipt, matching successful full recovery/deletion-replay receipt
  and an owner-protected isolation record. The latter has `environment: production`,
  `storageIsolationVerified: true` and a timezone-qualified `reviewedAt`; it attests a
  physical/privilege review, which the script cannot itself prove. The verifier checks
  pair integrity and matching backup/manifest evidence and rejects local rehearsals.
  Copy only its sanitized output back through an owner-reviewed private route. No
  destination credential is installed in the source runtime. Transfer loss leaves the
  earlier recovery point aging; it cannot turn into current verified health.
- Optional `capacity.json`: owner-reviewed qualification evidence with `scope: real-vps`,
  `environment: production`, timezone-qualified `measuredAt`/`expiresAt` and positive
  `sustainableRps` for the representative workload. Expiry or a local scope disables
  proximity alerts. Requalify after traffic-mix or runtime changes. No example capacity
  value is committed and none is automatically inferred from the workstation.

Use `python3 observability/verify-offhost.py --help` from this directory for the exact
input arguments. Keep all attestation inputs outside Git with no group/world write
permission. Runtime metrics contain only flags/times and the qualified rate; no backup
IDs, manifest paths, secrets or source account details enter metrics or Telegram.

Run `python3 scripts/test-production-observability.py --smoke` from the repository root
for the focused isolated proof. It starts fresh synthetic services, exercises the five
shared dashboards plus the operational dashboard, executes their SQL through the
restricted role, rejects sensitive samples and sends native Telegram firing/recovery
messages only to an HTTPS mock. Test-only loopback API listeners and a pinned mock
hostname are not production configuration. The ignored
`target/production-observability-evidence.json` records bounded synthetic workload,
head series, memory/CPU samples and data-directory disk usage. No raw requests, host
names/addresses, container IDs, credentials or personal data are retained there.

Real-VPS acceptance under #276 must repeat the annotated rule calibration with the
representative traffic mix and background/deployment overlap; validate active-series,
RSS/CPU and long-soak WAL/retention/compaction growth within the existing envelope;
verify cgroup/host filesystem mapping and job stop controls; prove real Keycloak and
SMTP/relay-quota observations, off-host backup/restore/attestation freshness and failed
attempts; exercise actual certificate expiry/renewal failures; deliver warning, critical,
missing-data and recovery notifications to the protected owner channel; and establish
an independent-device cadence for complete host loss. Recheck pinned dependency scans,
log-age enforcement and current trusted-source CI before the separate purchase/exposure
gates. The short workstation sample does not pass those checks or authorize more RAM.


## Host bootstrap and operating handoff to #276

**Prepared sequence; real-host steps are not exercised or authorized.** Use the
[release gates](../../docs/development/delivery-lifecycle.md#public-production-gates)
for purchase/exposure decisions and the [candidate audit](../../docs/research/public-production-candidate-acceptance.md)
for exact evidence and remaining work. The current deployment command unconditionally
refuses real-host operation; #276 must review any host adapter before using it.

1. After separate purchase authorization, qualify the selected Linux/x86_64 host,
   clock synchronization, Docker/Compose, private administration and provider console.
   Keep ingress/registration closed; prove IPv4/IPv6 firewall and Docker port behaviour.
   Record actual RAM/disk and the independent owner/storage access model before data.
2. Stage the reviewed checkout and successful trusted-main publication records. Select
   every image by its recorded digest, verify source/scans and configuration fingerprint,
   and retain previous identities. Render base Compose **with the observability overlay**
   into protected administration storage. Validate file consumers, model checksums,
   private networks and the secret inventory above. A base-only receipt is insufficient.
3. Generate independent production secrets outside Git; configure the realm, public-only
   backup encryption keyring and separately protected off-VPS decryption/signing keys.
   Prove archive/ledger isolation and independent second copies before opening accounts.
   Do not clone local/private-dev identity, data, secrets or TLS state.
4. For fresh empty state only, initialize the recovery gate, start PostgreSQL, complete
   the same-image Flyway actor, then start identity, edge and application through the
   reviewed host deployment procedure. Provision the Grafana reader after migrations,
   start telemetry privately, and verify readiness, image/source identity, routing and
   the complete synthetic browser journey. A failed phase keeps the candidate closed.
5. Review daily paired-backup and independent pull/verification schedules against the
   [recovery policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery).
   Serialize deployment, backup and heavy sync/localization. Backup deliberately leaves
   writers closed: inspect its receipt and explicitly thaw before starting services.
   Publish failed-attempt receipts too. Schedule no cleanup, copy, backup or sampler in
   this change; #276 must prove duration, missed-run detection and owner coverage first.
6. On the independent destination, verify the encrypted pair and isolated restore before
   publishing an off-host attestation. Exercise source-host loss, reconstruction, ledger
   replay, old-session revocation and evidence-checked reopening. Missing evidence keeps
   the gate closed; checksums alone do not establish recovery.
7. Activate the sampler/notifications only after #276 calibrates actual resources, quota,
   certificate, missing-data and failed-backup alerts and delivers firing/recovery to
   the protected owner channel. Verify complete host loss from an independent device.
8. For incidents use [production monitoring](../../docs/development/operations-runbook.md#production-monitoring)
   and the failure tables above. Stop background work first on capacity loss; close
   identity or global writes as appropriate for integrity risk. Preserve journals,
   source/configuration identities and sanitized receipts. Never delete a fence, create
   `READY` manually, restore a live volume or implicitly roll back schema to recover.

Public DNS/ACME, actual mail delivery, provider/legal checks and external acceptance
remain separately authorized #276 work. Successful private qualification does not
permit public signup or ingress. Private-dev retirement is a separate decision.
