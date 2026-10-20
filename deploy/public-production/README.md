# Public-production runtime configuration

- **Status:** Prepared runtime and recovery candidate (#275), pending real-host validation; never deployed

This directory holds the versioned runtime configuration for the future public
production host. No host, DNS record, public certificate or telemetry stack is operated for it.
Backup/recovery commands below are prepared for isolated rehearsal only. [ADR-0025](../../docs/decisions/0025-host-public-production-on-a-deferred-single-vps.md)
accepts the single-VPS design, but the [public-production gates](../../docs/development/delivery-lifecycle.md#public-production-gates)
remain open; nothing here authorizes a purchase, an exposure or a change to private-dev.

The [platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns the topology, trust boundary, resource envelope and recovery policy. This page owns
the secret inventory, the manual rotation and revocation model, and the boundary between
what the repository checks and what only a real host can prove.

| File | Owns |
|---|---|
| [`compose.yaml`](compose.yaml) | Services, networks, published ports, images, secrets, limits |
| [`caddy/Caddyfile`](caddy/Caddyfile) | Stock-Caddy HTTPS edge: hosts, routes, forwarding headers, bounds |
| [`bin/run-postgres`](bin/run-postgres) | Reads role secret files before the stock entrypoint drops supplemental groups |
| [`bin/run-keycloak`](bin/run-keycloak) | Keycloak entrypoint that reads secret files and refuses empty inputs |
| [`bin/run-application`](bin/run-application), [`bin/run-migrations`](bin/run-migrations) | Fail-closed recovery authorization before the shared secret wrappers |
| [`bin/recovery`](bin/recovery) | Paired backup, bounded copy/retention, isolated restore and deletion/readiness gates |
| [`bin/recovery-personal-data`](bin/recovery-personal-data) | Private recovery transport for the existing privacy commands and session revocation |
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
secret and resource contract, adapts the Caddyfile, exercises the real routes and
forwarding-header handling with a disposable internal CA and upstream stand-ins on
unpublished networks, and proves that each deliberate violation is detected.
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

`--stage A`, `B` or `C` stops after that stage; the default `D` runs all stages. Every
invocation creates fresh project-scoped volumes and networks, verifies migrations
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
- The future production promotion command, real-host migration/smoke and recovery,
  telemetry, the owner's Keycloak administration path, and the
  [canonical issuer check](../../docs/architecture/deployment/mvp-platform-and-delivery.md#canonical-issuer-contract)
  with the publicly served production certificate.
- Per-source and aggregate request-rate limits, which stock Caddy does not provide;
  the edge's body, header and timeout bounds are uncalibrated.

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
startup gate, sets all three database consumer roles to `NOLOGIN`, stops the edge,
application, migration and Keycloak, terminates and waits for existing database
sessions to roll back, then refuses unknown login roles, remaining connections,
prepared transactions or subscriptions. A runtime-volume lease excludes concurrent recovery actors even from different
owner journals or machines. Privileged PostgreSQL/host administration
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
that separate protected archive is reconciled.

After replay all restored realm sessions must be revoked, including offline sessions.
The recovery adapter uses realm logout followed by bounded discovery and
[consent/offline-token revocation](https://www.keycloak.org/docs-api/26.6.4/rest-api/#_delete_adminrealmsrealmusersuser_idconsentsclient),
then requires both persisted session stores to be empty for the restored realm.
A two-minute revocation budget leaves the gate closed on exhaustion; resume explicitly.

BFF sessions disappear with the stopped process. A failed replay leaves reopening
blocked and is retryable. `reopen` takes the same ledger/checkpoint arguments,
rechecks freshness, matching replay evidence and identity consistency, then authorizes
startup without starting services. Use the reviewed migration/readiness/smoke flow
afterwards. A restore receipt alone is never permission to start the application. Once readiness
passes, run `record-recovery` with the same runtime, backup and deletion-evidence
arguments. It binds the observed readiness, replay, startup gate, captured images and
manifest digest into the protected receipt accepted by retention. Keep that receipt
independently with the verified pair; the synthetic rehearsal's receipt cannot pin a
different real backup.

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
request with only Keycloak running and checks application readiness. All containers,
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
