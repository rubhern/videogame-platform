# Public-production runtime configuration

- **Status:** Prepared runtime, manual deployment and recovery candidate (#275), pending real-host validation; never deployed

This directory holds the versioned runtime configuration for the future public
production runtime. The [host foundation record](../../docs/development/operations-runbook.md#production-host-foundation)
owns confirmed prerequisites and outstanding host checks. Gameómetro remains
undeployed. The #275 deployment command remains rehearsal-only. The separate
[#276 first-install adapter](#private-first-installation-and-validation) is implemented
for future explicitly authorized private qualification; it has not run on the VPS.
[ADR-0025](../../docs/decisions/0025-host-public-production-on-a-deferred-single-vps.md)
accepts the single-VPS design, but the [public-production gates](../../docs/development/delivery-lifecycle.md#public-production-gates)
remain open; nothing here authorizes a purchase, an exposure or a change to private-dev.
[ADR-0033](../../docs/decisions/0033-select-lite-3-and-immutable-offhost-storage.md)
records the superseding #276 host/storage direction. Its cloud integration is not
implemented by this prepared candidate; #275 acceptance, digests and evidence remain unchanged.

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
| [`../../scripts/prepare_private_production.py`](../../scripts/prepare_private_production.py), [`../../scripts/install_private_production.py`](../../scripts/install_private_production.py) | Separately reviewed #276 private bundle and fresh-host bootstrap; no update, reset or public exposure |
| [`../../scripts/private_production_acceptance.py`](../../scripts/private_production_acceptance.py) | [Post-acceptance transition](#post-acceptance-runtime-transition) of an installed private runtime: restart policy, reviewed ceilings and the localization dashboard and view |
| [`../../scripts/private_production_upgrade.py`](../../scripts/private_production_upgrade.py) | [In-place upgrade](#in-place-upgrade-of-the-installed-runtime) of an accepted private runtime to validated trusted-main publications |
| [`bin/run-application`](bin/run-application), [`bin/run-migrations`](bin/run-migrations) | Fail-closed recovery authorization before the shared secret wrappers |
| [`bin/recovery`](bin/recovery) | Paired backup, bounded copy/retention, isolated restore and deletion/readiness gates |
| [`bin/backup-cycle`](bin/backup-cycle), [`bin/backup-watch`](bin/backup-watch), [`systemd/`](systemd/) | [Scheduled backup cycle](#scheduled-backup-cycle-276) and its independent supervision; prepared, not installed |
| [`bin/recovery-personal-data`](bin/recovery-personal-data) | Private transport for existing privacy commands: fenced live cleanup or closed-consumer restore replay |
| [`bin/identity-maintenance`](bin/identity-maintenance) | Identity deny/drain fence for the privacy cleanup, on the recovery gate and lease |
| [`runtime.env.example`](runtime.env.example) | Every non-secret host input, with placeholders |
| [`keycloak/`](keycloak/) | Production realm import and Keycloak abuse bounds, owned by #274 |

The application and migration actor reuse the environment-neutral private-dev secret
wrappers and the shared PostgreSQL bootstrap read-only; production changes neither.

## Remember Me provider compatibility

#374 prepares the same native Remember Me implementation as local/private-dev.
[ADR-0027](../../docs/decisions/0027-revoke-and-end-bff-sessions-in-the-application.md)
owns the policy and claim trust boundary. The shared
[Keycloak Dockerfile](../private-dev/keycloak/Dockerfile) compiles a small provider
against that exact pinned runtime's existing API JARs, targets its Java baseline,
and copies only the reproducible provider JAR into the final image before augmentation.
It downloads and bundles no new library and creates no second authentication mechanism.

Every Keycloak image update must rebuild the provider and pass real-Keycloak signed
false/true, silent-entry, missing-mapper, logout and BFF tests. The provider API does
not guarantee binary compatibility across upgrades; Keycloak labels it an internal SPI. Use matching reviewed image,
BFF and realm configuration; never activate the realm switch alone. Startup import
applies only to an absent realm. For an existing production realm, the shared
[alignment command](../private-dev/bin/configure-keycloak-identity) supports an explicit
`--origin <identity-origin> --realm gameometro --sessions-only`, with protected
`--admin-username` / `--admin-password-file` inputs. Run `--check`, review drift,
apply with `--snapshot-dir <protected-directory-outside-checkout>` and recheck during
an authorized rollout. It changes only the shared session fields and BFF mapper;
repeated applies without drift do nothing. Restore with the same target and scope
plus `--restore <session-snapshot>`. The command's full private-dev identity policy
must never be applied to production. Rollback must account for remembered SSO and
BFF revocation, not just restoring an older image. The
[in-place upgrade](#in-place-upgrade-of-the-installed-runtime) runs this command during
its reviewed sequence; do not run it separately on a runtime that upgrade owns.

This is prepared configuration, never deployed or publicly enabled. Actual HTTPS,
cookie and manual restart/renewal acceptance remains a real-host gate.

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

**This command's real-host refusal remains unconditional.** There is no approval-file
or environment-variable override. The separately reviewed [first-install adapter](#private-first-installation-and-validation)
is limited to empty private synthetic qualification. Real-host backup/recovery,
deletion replay, provider/privacy/release acceptance and the applicable
[gates](../../docs/development/delivery-lifecycle.md#public-production-gates) still
block real-data operation and public exposure. A local pass authorizes neither.

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

## S3 transport preparation (#276)

**Local preparation only; never activated.** The optional adapter behind `bin/recovery`
reuses the four-file encrypted pair, checksum verifier and staged local publication.
It creates no backup, database dump, account, bucket, credential, timer or application
change. The [platform recovery policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns approved policy; the [research evidence](../../docs/research/public-production-backup-storage.md#local-s3-adapter-evidence)
owns L1–L9 coverage and unexecuted P1–P8. The accepted #275 candidate remains frozen.

Use a dedicated owner-side Python environment with `s3-requirements.txt` for the optional
SDK; the existing filesystem commands require no SDK. Provide an owner-only regular
JSON file to `--s3-config`, containing `endpoint`, `region`, `bucket`, `accessKey`,
`secretKey`, and, for observation or expiry execution, `lifecycleSha256`. Endpoint must be the explicitly
confirmed full-region OVH HTTPS endpoint matching `region`; no default region/bucket/credential discovery
is provided. `lifecycleSha256` is SHA-256 of the reviewed S3 lifecycle `Rules` JSON,
serialized with Python `json.dumps(rules, sort_keys=True)`. Obtain and protect it in
the owner context; never learn an expected value from an untrusted runtime.
No configuration example contains real provider identifiers or secrets.

Prepared calls (private absolute paths are operator inputs, not provisioned resources):

```bash
# Independent read-only observer; no decryption key. Can bootstrap an empty inventory.
python3 deploy/public-production/bin/recovery observe \
  --s3-config /protected/observer.json --max-copy-bytes "$reviewed_byte_budget" \
  --receipt /protected/observation.json

# VPS capacity and configuration check: listing only; its receipt also admits an upload.
python3 deploy/public-production/bin/recovery admission \
  --s3-config /protected/admission-reader.json --max-copy-bytes "$reviewed_byte_budget" \
  --receipt /protected/admission.json

# Upload-only actor; input must be the existing complete encrypted pair.
python3 deploy/public-production/bin/recovery publish \
  --s3-config /protected/uploader.json --backup /protected/pair \
  --observer-receipt /protected/observation.json --max-copy-bytes "$reviewed_byte_budget" \
  --receipt /protected/publish.json

# Owner recovery: use a protected independently retained publish reference,
# or one complete pair from the protected observation's pairs array.
python3 deploy/public-production/bin/recovery fetch \
  --s3-config /protected/reader.json --publish-receipt /protected/pair-reference.json \
  --destination /protected/new-pair --max-copy-bytes "$reviewed_byte_budget" \
  --receipt /protected/fetch.json

# Independent expiry actor: default selection/report only; never sends DELETE.
python3 deploy/public-production/bin/recovery expire \
  --s3-config /protected/expiry.json --receipt /protected/expiry-plan.json

# Explicit execution, only after separate provider/activation authorization.
# Run independently of the VPS with a separate scoped identity.
python3 deploy/public-production/bin/recovery expire --execute \
  --s3-config /protected/expiry.json --receipt /protected/new-expiry-execution.json
```

Upload admission needs a protected successful observation at most one hour old. Missing
copy/restore-age alerts allow recovery captures; inventory/protection faults refuse admission. Known
incomplete attempts remain counted and alerted, and permit the next admitted capture; they are never
silently removed. The projected budget reserves space for the final receipt. It is not a provider
quota, concurrency reservation or an account spending cap. Incomplete multipart bytes, other stores
and concurrent uploads need independent consumption monitoring before activation. Use the
[approved D7 values](../../docs/research/public-production-backup-storage.md#owner-decisions-before-bucket-creation)
for private reviewed admission inputs. Automatic warning/forecast delivery and fixed policy wiring
remain activation work.

Each attempt uses unique keys and publishes its version-bound receipt last. The uploader
sends no retention, hold or bypass override. It checks an unsigned TLS provider `Date`
before mutation, and checks response dates and the admission window during transfer.
Single requests already accepted after a deadline cannot be rolled back; abandoned
versions remain locked and need reconciliation. Failed multipart uploads attempt abort;
SIGKILL/host loss and failed abort still need provider lifecycle cleanup. Requests have
bounded SDK timeouts and no automatic SDK retry; the whole operation has a 30-minute
Unix alarm. Streaming memory is bounded by one multipart part; inventories refuse more
than the adapter's fixed version/page limits. No complete dump is loaded into memory.

Run the observer with credentials/environment independent of the VPS, retain its output
there and transfer only the admission evidence to the uploader. The observer checks
lock/versioning/lifecycle, all versions/markers and protection before streaming each
complete pair through `fetch` and the existing verifier. It detects absent/incomplete
attempts, duplicate versions and overdue integrity/expiry evidence. Receipts are evidence
from that actor, not cryptographic proof of physical or administrative isolation.
`ciphertextIntegrity` never proves decryption, deletion replay or cloud-only recovery.

For full-restore age, optionally supply `--verified-receipt` to `observe`: a protected
owner evidence envelope containing the existing successful `recovery-rehearsal` receipt
fields plus the independently recorded `verifiedAt` timestamp. The original #275 receipt
format is unchanged. It must match an observed pair's backup ID and manifest hash and
have `scope: owner-reconstructed-target`, successful deletion replay and readiness.
An isolated-local rehearsal is refused as production recovery evidence. Integrity and
full-restore timestamps are reported separately. S3 evidence never sets the existing
filesystem attestation's `independentRecoveryVerified` flag.

Exit 0 means the operation completed without health alerts; exit 2 means observation or expiry
completed with alerts, and exit 1 is refusal/failure. Expiry execution preserves the plan's deadline
alerts in its receipt even when removal subsequently succeeds. Failure receipts remain sanitized. A
scheduler must deliver alert/failure and missing-observer-heartbeat notifications independently;
none is installed here. Keep receipts outside pair folders. The default expiry command lists
eligible exact versions/markers using only list/protection reads. `--execute` is valid only for
`expire` and builds a fresh plan; saved plans are not execution inputs. The execution identity also
needs read access to bucket lock/versioning/lifecycle configuration, with no configuration or
protection writes, object-content reads or bypass rights. Unknown prefixes, holds, inconsistent
protection/configuration and incomplete or changing inventories refuse execution. Active retained
versions are preserved. The executor sends only `delete_object` with `Bucket`, `Key` and a non-null
`VersionId`, including for markers. It rechecks protection/configuration before each deletion.
Compliance/hold enforcement at the provider remains essential for races after preflight and requires
P1–P5.

The protected execution receipt records the fresh plan and inventory fingerprint, lifecycle
fingerprint, version-specific intent before each request, acknowledgement times and a
complete final inventory fingerprint. Only final absence of every attempted version and
an unchanged remainder allow `absenceVerified: true` and success. A partial failure, lost
acknowledgement, uncertain response or failed final listing leaves attempts `unconfirmed`,
even if a request may have deleted bytes. Retain that receipt and reconcile it with a fresh
complete inventory; retry with a new receipt path re-plans remaining versions. Do not
replay an old plan or treat an empty retry as confirmation of the previous receipt.

Receipts are durably checkpointed before mutation and after acknowledgements. A checkpoint
write failure stops progress; SIGKILL/host loss can leave an older checkpoint or temporary
receipt requiring reconciliation. Absence is logical provider-visible absence, not proof
of physical reclamation. Requests have no SDK retry; execution shares the fixed call,
version/page and 30-minute budgets in `recovery_s3.py`. No VPS connection, Compose inputs,
recovery keys or rehearsal receipt is needed for expiry. Preserve receipt files outside
the VPS and pair folders.

Do not interpret a plan as verified absence or retention compliance. The legacy `retain`
command remains solely for filesystem copies. Remote ledger/archive durability barriers,
cloud attestation acceptance and real cloud-only recovery are required before activation.

## Scheduled backup cycle (#276)

**Prepared and rehearsed locally; never installed, and blocked until the owner activates it.**
Nothing in the repository enables a timer, a workflow job or a provider resource. The
[platform recovery policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns cadence and ceilings; [the research](../../docs/research/public-production-backup-storage.md#local-s3-adapter-evidence)
owns the evidence and its limits.

[`bin/backup-cycle`](bin/backup-cycle) runs on the VPS at 02:30 and 14:30 UTC. It reuses the
existing actor, lease, gate and encrypted pair, and adds no backup format:

1. **Check without a fence.** Public key, separate credentials, open gate, no identity
   maintenance, pending journal, unfinished upgrade or running synchronization, required services
   running, disk and tmpfs for the database sizes, and S3 admission. Any refusal skips the slot with
   no downtime.
2. **Capture under one lease,** within a 30-minute fence budget by default.
3. **Reopen** the roles and gate, start Keycloak, the edge and the application in that order,
   restart Grafana if it was running, and check readiness.
4. **Upload** only after reopening, with a fresh admission for each attempt.
5. **Keep** only the last uploaded pair on the host, and receipts for 40 days.

`CYCLE.json` records each step before it happens, so the
[reconcile timer](systemd/gameometro-backup-reconcile.timer) finishes whatever a crash or reboot
left, within two minutes of boot and every ten minutes afterwards.

| Failure | Result |
|---|---|
| Any predictable condition in step 1 | Slot skipped; product untouched; failure reported |
| Capture fails, is interrupted or exceeds its budget | Own fence undone, product restarted, nothing uploaded, failure reported |
| Thaw fails after a valid pair | Own fence undone, and that pair still uploads |
| Host dies or reboots during the capture | Reconcile reopens; a published pair uploads if its window is open |
| A required service or readiness fails | Retried by reconcile; the owner is alerted after three failed reopenings |
| Grafana fails to return | Reported as degraded; never fences the product |
| Upload or admission fails | Product stays open; retries at 15, 30, 60… minutes inside the six-hour window |
| Window closes | Pair is never uploaded late, because its lock would pass the ceiling; the next slot replaces it |
| Fence owned by a manual backup, upgrade, restore or replay | Never touched. `owner-required` if it replaced this tool's fence, and captures stop |

**Reopening is limited by ownership.** The actor's `reopen_capture` accepts only the two capture
phases, recorded with this run's identity. A capture only reads the databases, so undoing its fence
loses nothing. Manual `backup`, whose fence the in-place upgrade depends on, keeps its explicit
`thaw` and records no owner. A running synchronization defers the slot rather than being interrupted;
a long run can therefore age the upload until the alerts below fire.

**Availability cost.** The fence stops the edge, so the product is unreachable, without a maintenance
page, for the whole capture twice a day. Its duration on the real host is unmeasured.

### Protected inputs and provider identities

Create these only after separate owner authorization. No repository file contains their values.
`/etc/gameometro/production/backup-cycle.json` is owner-only, with exactly these fields:
`authorized` (`true` only at activation), `admin`, `project`, `state`, `keyring`, `recipient`, `work`,
`uploaderConfig`, `admissionConfig`, `s3Python` (a virtual environment holding `s3-requirements.txt`),
`status` (the sampler's `--backup-receipt` path), and optionally `maxRetainedBytes` and
`maxFenceSeconds`. The admission reader's file needs the same `lifecycleSha256` as the
[S3 configuration](#s3-transport-preparation-276).

| Identity | Held in | Rights | Denied |
|---|---|---|---|
| Uploader | VPS | Put and multipart create/part/complete/abort under `pairs/` | Reads, listing, deletion, retention, holds, bypass, configuration |
| Admission reader | VPS | List versions; read lock, versioning and lifecycle configuration | Object content, every write and delete |
| Observer | GitHub environment `backup-observer` | List versions; read exact versions, retention, holds and configuration | Every write and delete |
| Expiry | GitHub environment `backup-expiry` | List versions; read retention, holds and configuration; delete by version ID | Put, retention, holds, bypass, configuration writes |
| Recovery reader | Owner context | Read exact versions | Everything else. P5 must prove it can read uploader-owned objects |

Each GitHub environment needs `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`,
`S3_LIFECYCLE_SHA256`, `NOTIFY_TELEGRAM_TOKEN` and `NOTIFY_TELEGRAM_CHAT`, restricted to `main`. The
observer may also hold `RESTORE_EVIDENCE`, the envelope described under
[S3 transport](#s3-transport-preparation-276); without it the observer reports `restore-missing`.

### Independent supervision

[`backup-watch.yml`](../../.github/workflows/backup-watch.yml) runs hourly and is inert until the
repository variables `BACKUP_OBSERVER_ENABLED` and `BACKUP_EXPIRY_ENABLED` are `true`. The observer
hashes each pair once, newest first and at most two per run, keeping its checkpoint in the Actions
cache; a lost checkpoint only re-hashes. The expiry job deletes exact versions after a fresh plan.
The repository is **public**: logs and artefacts are readable, so the tool prints fixed lines, uploads
no artefact, and sends findings to the private channel only. A changed finding is sent at once, and
every finding, healthy included, at 07:00 UTC. A missing daily message is the dead-man signal: GitHub
schedules are best effort and disable themselves after 60 days without repository activity.

On the host, `sample-host.py` reads the cycle status as its `--backup-receipt`, driving the
[stale-upload and needs-the-owner alerts](../../docs/development/observability.md#production-alert-interpretation-and-calibration).
`verify-offhost.py` accepts an `s3-fetch` receipt, so a weekly owner restore can feed the existing
attestation. Forecast alerts at EUR 2 and EUR 3 are configured in the OVHcloud console, not here.

### Validate before activating

1. **Provider:** P1–P5 pass on a disposable bucket, and P6–P8 are recorded.
2. **Local:** `python3 scripts/test-production-backup-cycle.py`, then, from `scripts/`, the disposable
   real-runtime rehearsal:

   ```bash
   python3 rehearse-production-backup-cycle.py --revision "$TRUSTED_MAIN_SHA" --evidence /protected/new-directory
   ```

3. **Host, read-only:** with `authorized` still false, `backup-cycle check` must report every line
   `ok` except `authorized`.
4. **Host, one supervised cycle** in a low-traffic window, once `authorized` is true: start
   `gameometro-backup.service` by hand; measure the fence; confirm the status file, the external
   product journey and an upload.
5. **Independent proof:** dispatch the workflow; confirm the pair is hashed, then `fetch` it from a
   clean owner context and complete a full restore and deletion replay.
6. **Failure drills on the real host:** reboot during a capture and confirm reconcile reopens; revoke
   the uploader and confirm the product stays open and the alert reaches the owner; stop the workflow
   and confirm a missing heartbeat is noticed.
7. **Expiry:** prove exact-version deletion on the disposable bucket before enabling its job.

Activation is a separate owner decision, after the remaining gates in
[Cloud transport dependency](#cloud-transport-dependency):

```bash
sudo install -m 0644 deploy/public-production/systemd/gameometro-backup* /etc/systemd/system/
```

```bash
sudo systemctl enable --now gameometro-backup-reconcile.timer gameometro-backup.timer
```

### When the owner is needed

`owner-required` stops all automatic action. Inspect `CYCLE.json` and the pending journal; resolve the
fence with the procedure that owns it, never by creating `READY`; then run `backup-cycle acknowledge`,
which refuses while the gate is closed. To hold a fence for inspection, stop
`gameometro-backup-reconcile.timer` first.

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
is not a recovery procedure. The [in-place upgrade](#in-place-upgrade-of-the-installed-runtime)
shares this actor's runtime-volume lease and respects the startup guard.

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
an incomplete directory into a published pair. A manual failure leaves the journal, gate and
role fences closed. Completed backups also stay closed until explicit `thaw`; it
refuses an interrupted backup. Inspect the journal and service/role state privately
before recovering an incomplete capture; make a new pair rather than guessing that
it succeeded. Only the [scheduled cycle](#scheduled-backup-cycle-276) reopens by itself, and only its own fence. `thaw` removes only a completed backup fence and starts no services:

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

**Existing #275 filesystem-copy capability, not the selected #276 cloud path.**
The [current recovery target](../../docs/architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
requires direct automated encrypted S3 uploads; the optional
[S3 preparation](#s3-transport-preparation-276) remains unactivated. The following
documents the existing filesystem command contract.

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

The former mandatory Lenovo destination is superseded by the linked platform
policy. An optional Lenovo copy is expendable and does not establish independent
recovery or key isolation. Keep rolling backups, legal archives, deletion ledgers
and independent copies in the separate protected stores required by that policy;
backup pruning must never operate on the archive or ledger. Do not use a plain
filesystem pull receipt as evidence of the unimplemented cloud guarantee.

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

Before public authorization, #276 must prove: cloud version/credential isolation
and independent administration/key custody, physical/account independence and second
ledger/archive copies; least-privilege direct-upload/verification/expiry identities,
connectivity, capacity, interruption handling and destination integrity checks;
encrypted/disabled swap and plaintext scratch limits;
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
  by `observability/verify-offhost.py`. This is the existing filesystem/pull contract;
  it cannot certify the new cloud target without an implemented, reviewed adapter
  and evidence contract. Supply its verified encrypted pair directory,
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


## Private first installation and validation

**Implemented; private VPS qualification remains incomplete.** The
[preparer](../../scripts/prepare_private_production.py) and
[installer](../../scripts/install_private_production.py) reuse the
[signed #275 candidate](../../docs/research/public-production-candidate-acceptance.md#owner-acceptance-record)
without repeating its accepted campaign. They neither select a newer product nor
mount a newer realm or wrapper into the accepted images. The old deployment
command and its configuration verifier retain their refusal rules.

There are two independently reviewed identities: the accepted product/runtime and
the new operational rollout. The installer requires an exact **published trusted-main
operational revision**, its adapter fingerprint and the reviewed private configuration
hash. It verifies current retained quality/security/publication evidence, all imported
operator sources against that operational revision, the complete exported candidate
bytes/modes against its immutable tree, the signed runtime fingerprint through the
existing verifier, and every selected image's registry availability/platform. A
locally edited or unpublished adapter cannot bootstrap a host. This is an operational
compatibility binding, not a change to #275's acceptance or product version.

The preparer renders the candidate base **plus observability**, then applies only the
reviewed private delta: remove all published ports, use supplied private TLS material,
give the BFF its certificate-only truststore, disable automatic service restart until the
[post-acceptance transition](#post-acceptance-runtime-transition), and
explicitly disable bridge IPv6/use protected IPv4 NAT. Internal data networks, fixed
proxy addresses, origins, resource limits, mounted wrappers, secret consumers and
maintenance controls remain intact. The validator compares the complete result with
that exact delta; extra publications, privileges, host networking, mounts or environment
changes refuse. Use the final JSON alone; never merge the public Compose back into it.
Regenerate a previous preparation bundle when the adapter/delta changes.

Post-start validation compares effective process argument vectors resolved from the
reviewed Compose overrides and immutable image defaults with Docker inspection.
Compose dollar escaping and Docker's absent-argument representations are normalized;
executables, argument content, order and boundaries must still match. A genuine
process mismatch remains a startup refusal subject to the interruption procedure
below. This operator-only correction requires a newly reviewed and published
operational identity; it does not change the accepted product candidate or authorize
retrying a claimed installation.

The bridge settings use [Docker's protected NAT mode](https://docs.docker.com/engine/network/port-publishing/#gateway-modes);
`nat-unprotected` is not an acceptable substitute.

### Local preparation

Use Python 3.12+, Git with the accepted candidate available, Docker Compose/Buildx and
authenticated read-only GitHub/GHCR access. No secret value belongs in `runtime.env`.
The input inventory is the candidate's existing example; the preparer replaces its
image/version placeholders with the verified accepted publications.

```bash
python3 scripts/prepare_private_production.py \
  --output /new/local/protected-private-bundle \
  --env-file /absolute/path/to/non-secret-runtime.env \
  --tls-dir /etc/gameometro/production/private-tls
python3 scripts/install_private_production.py --print-adapter-fingerprint
python3 scripts/test-private-production-preparation.py
python3 scripts/test-private-production-installation.py
python3 scripts/test-private-production-control.py
```

Review `compose.candidate.json`, `compose.private.json`, `Caddyfile.private` and
`preparation.json`. The bundle contains the unmodified candidate operational export;
its absolute bind paths target the separate host directory recorded in the receipt,
not the workstation or `/srv/gameometro/repo`. A partial bundle with no complete
matching receipt is unusable. Keep its parent 0700; the non-secret Caddyfile is 0644
so the capability-dropped edge can read its bind mount. The two JSON configurations,
env file and receipt stay 0600. The preparer makes only candidate directory bind
sources and their subdirectories readable/searchable by non-root consumers. Host-only
ancestors stay private: Docker resolves them before mounting the source. Candidate
file bytes and executable modes remain unchanged and are verified against the accepted
tree. Preserve directory modes when staging; do not apply a recursive permission
change to the bundle, secret/TLS directories or administrative storage.
Preflight checks candidate file binds and every directory bind descendant for consumer
access as well as protected ownership/type/ACLs. An incompatible mount refuses before
the intent or journal is claimed, including the repeated preflight under the deployment
lock. These metadata checks do not start containers or prove application behaviour.
No image build, service start or secret generation
occurs during preparation.

### Future authorized host sequence

**Prepared sequence; the failed attempt grants no runtime acceptance.** Owner review and a
separate request to publish the operational change precede host execution; this
change grants neither. After successful trusted-main CI on that exact revision,
record the reviewed adapter/configuration hashes and reassess the candidate's
security conditions for the intended private operation. Public opening retains its
separate eMudhra positive/negative TLS condition and all release gates.

1. With separate staging authorization, put the published operator sources in a
   root-owned protected operational checkout, for example
   `/srv/gameometro/operations/<reviewed-operational-sha>`. Stage the bundle at the
   exact target in its receipt, preserving bytes/executable modes and making all
   sources root-owned. Keep the existing administration checkout separate. Every
   protected-path ancestor must be root-owned, non-writable by group/others and
   free of symlink traversal. The adapter makes no ownership/permission exceptions.
2. Stage the owner-supplied [base secrets](#secret-inventory) and
   [observability inputs](#production-observability-candidate), approved model and
   TLS material. The secret directory/files retain 0750/0640 and their configured
   consumer group. All fourteen file inputs must exist; only disabled IGDB/Telegram
   values may be empty. Keep `/var/lib/gameometro/production` 0700, with a new empty
   `metrics/` at 0755. The model directory must be readable/traversable by its
   non-root consumer, with the complete approved checksum inventory. Stage no
   `first-install/` directory or intent: the installer claims both exclusively.
3. Supply `ca.crt`, `edge.crt` and a certificate-only `truststore.p12` at 0644 in
   `/etc/gameometro/production/private-tls` (0700), and `edge.key` at 0600, all
   root-owned. The leaf covers both canonical hosts, matches the key and remains
   valid beyond the next day. The PKCS12 store contains the private CA and retains
   the accepted JVM's normal roots; its `changeit` integrity password is public,
   not a secret. Keep every CA signing key off the VPS. Generate/import these
   through the owner's reviewed key procedure; the installer creates none.
   Before staging, run `scripts/validate_private_production_truststore.py --help`
   and validate the supplied store against the accepted image's **actual JVM** on
   an authorized workstation with that image already available. This disposable
   `keytool` invocation has no network and never starts the application. It rejects
   stores containing keys, missing JVM default roots, missing the private CA, or
   PKCS12 bags ignored by Java. Review and stage its `truststore.validation.json`
   at 0600 alongside the TLS inputs; independently record its SHA-256. The host
   binds the receipt to the exact image, validator, CA and store bytes. An OpenSSL
   parse alone is insufficient.

   ```bash
   python3 scripts/validate_private_production_truststore.py \
     --directory /absolute/local/public-certificate-inputs \
     --receipt /absolute/local/public-certificate-inputs/truststore.validation.json
   sha256sum /absolute/local/public-certificate-inputs/truststore.validation.json
   ```
4. Establish protected root-side read-only GitHub Actions/contents and registry access,
   with the required tools already installed. Run the following **without** `--install`
   first, over existing private administration. Supply an existing root-owned 0700
   evidence directory outside the installation journal. Preflight writes only
   protected sanitized evidence there; it never mutates Docker or host settings.
   Failed and inconclusive checks are retained too. The variables below are the independently recorded
   reviewed values, not values selected automatically from a mutable checkout:

   ```bash
   installer=/srv/gameometro/operations/REVIEWED_SHA/scripts/install_private_production.py
   sudo python3 "$installer" \
     --operational-revision "$REVIEWED_OPERATIONAL_SHA" \
     --adapter-fingerprint "$REVIEWED_ADAPTER_SHA256" \
     --configuration-sha256 "$REVIEWED_CONFIGURATION_SHA256" \
     --truststore-evidence-sha256 "$REVIEWED_TRUSTSTORE_RECEIPT_SHA256" \
     --evidence-dir /var/lib/gameometro/private-qualification-evidence
   ```

5. Inspect the preflight and authorize the **first installation** separately. Invoke
   the same command/identities with `--install`. It rechecks preflight under the
   exclusive deployment lock, claims a new journal, pulls only the immutable images,
   then takes the recovery actor lease. Any pre-existing labelled **or unlabelled**
   target container, network, volume, startup/identity fence or journal refuses.
   The only gate-volume exception is the empty volume just claimed by that lease;
   it must contain solely its lock file. Listing errors never count as emptiness.
6. The adapter invokes the candidate recovery actor's unchanged `initialize`, then
   starts PostgreSQL, completes same-image Flyway and checks the accepted migration
   endpoint before provisioning the Grafana reader. Only then does it start Keycloak,
   localizer, private edge and monitoring, followed by the application and smoke.
   All starts use `--no-deps`: Compose cannot start the application before migration.
   The recovery and identity fences, live image/health, restart policy and zero-port
   contract are rechecked. No acquisition, cleanup, backup or notification schedule
   is enabled; no accounts or user writes are created by the smoke.

Preflight is read-only and validates the reviewed Debian/x86_64/local Unix Docker
context without rootless/user remapping, synchronized clock, cgroups, Docker packet-path protection, source/config
identities, exact inputs, effective consumer groups, secret content bounds, TLS chain,
both names/key/CA store, model and host/Docker/tailnet subnet conflicts. It prints
no secret bytes or raw Docker/environment output. Host configuration and independent
external IPv4/IPv6 denial still require actual authorized qualification; static
checks are not evidence that the provider firewall was tested.
The subnet inventory accepts null IPAM configuration only for Docker's built-in
`host`/`host` and `none`/`null` name/driver pairs, which allocate no subnet. Missing
or malformed IPAM configuration and null configuration on other networks refuse;
host routes and allocated Docker subnets still undergo the overlap check.
The active systemd daemon's arguments and TCP listeners are inspected as well as its
configuration file, whose modification time must conclusively predate daemon startup.
Exact supported Engine/Compose pairs are executable allowlists, not minimum-version
comparisons: the owner-reported target 29.9.2/5.6.1 still needs real-host qualification;
local mechanism evidence uses 29.8.2/5.6.0. Unknown versions, flags, configuration or
network options refuse. Disabled filtering, direct routing, live restore, unexpected
logging, daemon TCP administration, Swarm and remapping refuse. Effective container
mounts, capabilities, namespaces, resources, network membership/options, IPAM and
port bindings are checked against the reviewed configuration after startup too.
Evidence records version/policy decisions and inconclusive external denial without
raw environment, listener, container or secret output. Available memory must cover the configured steady-service
ceilings plus the platform's free-memory reserve; both the runtime and Docker storage
must retain the platform's free-disk margin, rechecked after image pulls. These are
startup refusal controls, not workload/capacity acceptance.

### Private tests, receipts and failure handling

The built-in smoke verifies the running application's source/version, health, shell,
bounded anonymous catalogue result (including the approved empty outcome), denied
management/master-realm routes, positive private TLS and a negative untrusted-CA
request. It runs the **candidate's** account-free issuer checker against native
Keycloak discovery and the BFF's real authorization redirect, including spoofed
headers. A one-shot bounded helper joins the existing telemetry network, whose edge
aliases resolve both canonical names. The BFF retains its application-edge issuer
alias and verified JVM truststore; its actual consumer must load that store. No host
port, SSH tunnel workaround, globally installed CA or insecure TLS option is added.
This does not yet prove an authenticated token exchange, real email delivery, a
full synthetic browser journey, public ACME or certificate renewal. Those private
identity tests may use separately authorized synthetic identities and the same
unpublished networks/CA; real accounts remain prohibited while recovery is pending.

Before any Docker mutation, an exclusive durable
`/var/lib/gameometro/production/first-install-intent.json` identifies the attempt.
The new `first-install/installation.json` records phases before mutation, source,
CI/run identities, image set, operational/configuration/schema hashes and migration
version. Runtime configuration has per-attempt run/configuration labels. Its
`recovery/` journal and initialization receipt remain alongside it. Writes use
unique temporary files, atomic replacement and directory fsync; incomplete writes
remain evidence. Journal-creation/first-write failures are handled too. A failure
sidecar outside the journal takes precedence over a stale success record. The
terminal state is first `prepared`; `success` follows the lease completion handshake
and helper exit. Raw actor output and credential values are withheld.

On a detectable cancellation, migration, startup, TLS, smoke or receipt failure,
the controller fences the gate and physically stops only exact container IDs whose
attempt, configuration, Compose project and service labels all match. It retains a
duplicate of the **actual flock descriptor**, transferred from the helper through a
private root-only Unix socket; helper death therefore cannot release ownership
before scoped shutdown. No Docker socket is mounted in a helper. The controller
never uses a project-wide stop. If it lacks that descriptor, it must reacquire the
same gate lock and validate the attempt/fences; another legitimate lease or unknown
maintenance fence prevents any stop. Cleanup has a 90-second operation budget
(with bounded command termination), then reports unconfirmed completion rather
than retrying indefinitely. Volumes and journals are preserved; migrations are
never reversed. A cancelled/in-flight Docker request may complete after its CLI
exits: such evidence stays inconclusive even when the observed containers stopped.
After verified ownership and successful gate fencing, an I/O failure in the local
recovery journal does not skip the scoped physical stop. The persistence error is
still propagated after the stop attempt; if shutdown fails too, both errors are
retained. Such a close never returns confirmed success despite the journal error.

`SIGINT`, `SIGTERM`, `SIGHUP`, observable stdin EOF and helper loss request this
cleanup. Keep the installer attached to the interactive SSH session; do not use
`nohup`, detach it, or redirect stdin from `/dev/null`. Detection begins when the
OS reports teardown, not when a physical link first disappears. `SIGKILL`, kernel,
host or daemon failure cannot promise cleanup: the helper normally fences on EOF,
but existing services may remain active. Zero published ports and `restart: no`
remain essential; no reboot/recovery acceptance follows from them.

After any interruption, inspect locally using the reviewed installer:

```bash
sudo python3 "$installer" --inspect-interrupted
sudo python3 "$installer" --reconcile-interrupted \
  --operational-revision "$REVIEWED_OPERATIONAL_SHA" \
  --adapter-fingerprint "$REVIEWED_ADAPTER_SHA256" \
  --configuration-sha256 "$REVIEWED_CONFIGURATION_SHA256"
```

Inspection is diagnostic only. Separately authorize reconciliation: it validates the
immutable local intent, reviewed operator identity, runtime journal, labels and gate
ownership, closes the gate and stops only this attempt. It does not start services,
open a gate, repair a corrupt journal or re-run preflight's network/API dependencies.
Missing/contradictory state, concurrent ownership or historically uncertain commands
remain inconclusive and require an owner-assessed recovery; a zero diagnostic exit
code is not service readiness. Preserve every reconciliation receipt and pending
write. Do not erase history to make the operation retryable.

Once a journal has been claimed, never rerun first install after success, failure or interruption, delete a journal or
volume, create `READY`, remove a fence, reverse Flyway or run an ad-hoc Compose start
to get past a refusal. There is no previous release to roll back to at first install.
Use an assessed forward fix or separately authorized paired recovery on an empty
target; any disposal needs a separate exact-target decision. Updates use the separate
[in-place upgrade](#in-place-upgrade-of-the-installed-runtime). This adapter deliberately
implements no update, force, reset or rollback mode.

### Failed synthetic bootstrap disposition

**Proposal for separate owner review and exact-target authorization; not executed.**
The [host evidence](../../docs/development/operations-runbook.md#failed-private-bootstrap)
records the failed attempt. Correcting bundle permissions does not make its claimed
journal reusable. Keep it fenced and stopped while choosing a disposition. This
procedure supplies no deletion or restart command: live resource IDs,
configuration hashes and scope must first be independently verified and approved.

1. Collect a protected evidence copy of the original intent, installation/failure
   sidecar, runtime JSON, recovery journal, preparation receipt and any pending writes.
   Hash the copies and retain the original operator revision/fingerprint, accepted
   candidate/image identities, private configuration hash and timestamps. Never
   overwrite the old receipt with a newly prepared bundle's identity.
2. Under exclusive local installation ownership and the original recovery actor lease,
   compare the exact attempt UUID across intent, journal, failure sidecar and gate
   owner. Compare configuration hashes and the runtime's sole per-service label delta
   against the original reviewed private rendering. Verify the original published
   operational sources, candidate tree/fingerprint and pinned images independently.
   A newer adapter cannot assert the old operational identity for reconciliation.
   If inspection shows an active actor, uncertain in-flight request, missing evidence,
   conflicting owner, identity-maintenance fence or unexpected gate contents, stop for
   investigation. A historical `confirmed` shutdown is insufficient on its own.
3. Inventory actual container IDs, image IDs/digests, mounts, project/service/run/config
   labels, state and restart policies; require all attempt containers stopped, no
   active helper/writer and no published port. Inventory exact volume names, drivers,
   mountpoints and Compose labels from the original runtime, and network IDs, labels,
   subnets and endpoints. Volumes/networks do not carry the attempt labels: project
   names alone cannot prove ownership. Require recorded creation/mount relationships
   and no unrelated consumers. Do not inspect arbitrary Docker storage by name prefix.
4. Independently verify the PostgreSQL volume's exact identity and **entire** contents
   read-only with the accepted bounded helper, no network, no PostgreSQL entrypoint
   and no writable mount. Absence of `PG_VERSION` alone is not emptiness. Confirm no
   Flyway/application/Keycloak state, accounts, ratings or other valuable data exists;
   any entries or incomplete inspection invalidate the empty-state disposal proposal.
   Preserve the owned recovery gate's owner/fence evidence; never manufacture `READY`.
5. **Preservation option:** retain all state and prepare a separately reviewed forward
   recovery procedure with original installed-state identities and closed-gate checks.
   The first installer and stop-only reconciler cannot resume initialization. Starting
   PostgreSQL manually, editing the journal or merely fixing permissions is not an
   approved recovery. Nonempty state requires a new data/schema assessment before
   selecting forward recovery or an independently verified paired restore.
6. **Empty synthetic disposal option:** submit a finite manifest of verified stopped
   container IDs, the exact empty `postgres-data` volume, the owned `recovery-state`
   volume and only actually created, exclusively owned network IDs. At phase `postgres`,
   later service/data resources are unexpected and require investigation. Include the
   protected evidence archive destination and exact original administrative paths:
   `first-install/`, `first-install-intent.json` and this attempt's failure sidecar under
   `/var/lib/gameometro/production`. After separate owner approval, a reviewed operator
   may remove only those Docker objects, leaving the gate until last and preserving
   evidence before archiving the original claims. Keep the installation lock inode
   intact. Recheck identity, inactivity and exclusive ownership immediately before each
   action; any drift stops the procedure. Never use project-wide `down -v`, pruning,
   wildcards or recursive deletion of administrative storage. Exclude secrets, TLS,
   model, metrics, images, other installations, private-dev and all independent backup,
   legal and deletion-ledger stores.

Disposal is not a retry or an installation authorization. Any later fresh qualification
needs a separately approved bootstrap plan, corrected newly prepared bundle, published
trusted operational revision/fingerprint, current trust evidence and read-only preflight
showing exact resource/claim absence. Keep #275's accepted source, images and signed
acceptance unchanged; never clear state merely to pass the existing installer.

### Post-acceptance runtime transition

**Implemented for review; not executed on any host.** First installation keeps every
service at `restart: no` on purpose. [`private_production_acceptance.py`](../../scripts/private_production_acceptance.py)
is the one reviewed step out of that state, for a runtime the owner has accepted. It is
not an update path: images, the accepted candidate, its fingerprint and Flyway history
do not change, and it grants no public, backup or recovery acceptance.

The candidate's bundle is immutable, so a change to this directory never reaches an
installed runtime by itself. The transition derives one configuration from the installed
journal, and its validator refuses any other difference:

| Service | Change |
|---|---|
| Ten persistent services | `restart: unless-stopped` |
| `catalogue-localizer` | The [reviewed memory ceiling](compose.yaml), by recreating the container |
| `grafana` | [Plugin policy](compose.observability.yaml) and the reviewed dashboard as one read-only file over the candidate's copy, by recreating the container |
| `migration` | None: it stays `restart: no` and is not started |

The other eight services change policy in place through `docker update`, with no restart,
and only the two recreated services are optional for product readiness. The intended
configuration and a record stay in `/var/lib/gameometro/production` as
`compose.accepted.json` and `runtime-acceptance.json`. Use that file for any later Compose
command on this runtime: `compose.private.json` would revert policy and ceilings when it
recreates a service.

The running application is unchanged. The
coverage view reaches this runtime ahead of its Flyway history: the transition applies the
same idempotent migration file under the migrator role, grants it to the existing reader and
gives that reader the settings `provision-reader.sql` owns. A later application update replaces
the view with itself. Nothing else is applied.

Refusals, all before any runtime or database change:

- no recorded successful installation, or a failure sidecar, reconciliation, pending write
  or recovery journal `PENDING.json`;
- a closed recovery gate, identity maintenance, or a gate owned by another attempt;
- a held recovery lease, including a running backup;
- a required service that drifted from the installed configuration. A stopped localizer or
  Grafana is accepted, because the transition starts both;
- a reviewed-hash, operational-revision or owner-reference mismatch;
- no paired backup manifest from the last 24 hours with the installed image identities.

Restart behaviour, proved on a disposable real daemon by
`scripts/test-private-production-restart-docker.py`: a crash or daemon loss restarts an
accepted service, including an abrupt daemon loss; `docker stop`, `docker kill` and Compose
stop survive both, so a stopped service stays stopped until an operator starts it. One-shot
actors do not rerun. A closed gate keeps its protection on every restart: the application
and migration wrappers refuse before reading any secret, and a backup leaves their
containers stopped. Daemon restart gives no start order, so the application may exit and
retry until PostgreSQL is ready. A real host reboot remains to be exercised.

Rollback limits: `--revert-restart` returns all ten services to `restart: no` in place and
works while the gate is closed. It keeps the higher ceiling, plugin policy, dashboard and
view, which are harmless without their consumers. Reversing those needs a separately
reviewed recreate. The view is additive and never needs removal for an older application.
The [runbook checklist](../../docs/development/operations-runbook.md#private-production-localization-and-grafana-stabilization)
owns the authorized sequence. Once an upgrade claims the runtime, this transition refuses:
its file no longer describes what runs.

### In-place upgrade of the installed runtime

**Implemented for review; not executed on any host.**
[`private_production_upgrade.py`](../../scripts/private_production_upgrade.py) moves an
[accepted](#post-acceptance-runtime-transition) private runtime to the validated publications
of one newer trusted-main commit. It is not a first install, a deployment platform or
public acceptance, and it never edits the installation journal or the signed candidate. The
[runbook checklist](../../docs/development/operations-runbook.md#private-production-in-place-upgrade)
owns the authorized sequence; this section owns the contract.

| Mode | Effect |
|---|---|
| None (plan) | Read-only: prints the plan and its hash. Its only effects are a private scratch directory it removes and bounded helper containers without network or write access to state |
| `--stage` | Writes the immutable bundle `/srv/gameometro/installations/<sha12>-private` and pulls absent pinned digests. The runtime is untouched |
| `--apply` | Runs the upgrade under the backup fence, for the reviewed hash and an owner reference |
| `--resume` | Fences again and repeats the same claimed upgrade |
| `--abandon` | Returns an upgrade whose schema and identity did not advance to the backup's own fence, so `recovery thaw` works |
| `--forward-fix-of <failed-sha>` | With plan, `--stage` and `--apply`: replaces a failed target with a newer published one, under the original recovery point |
| `--verify`, `--inspect` | Read-only comparison with the activation; journal and fence diagnostics with the recovery boundary |

There is no force, skip or rollback mode. Target selection is the full source SHA alone: the
digests come from that commit's retained publication records, checked against its
successful build and security runs and strict ancestry over the active source.

**What the target is.** The plan exports the target's operational sources, verifies them
against the trusted Git tree, and renders that source's base and observability Compose with
the installed non-secret inputs and the
[reviewed private delta](#private-first-installation-and-validation). It then compares the
result with the active configuration:

| Difference | Outcome |
|---|---|
| None, and identical bound bytes | The service keeps its exact definition and is not recreated. PostgreSQL always takes this path |
| Published image (application, migration, Keycloak, edge, localizer) | Allowed, then recreated |
| The exact [private administration](#private-production-administration) settings | Allowed only at their reviewed values; master frontend alignment stays under the upgrade lease |
| The reported version, a new non-identity setting, a read-only bind from the target bundle or TLS inputs, a higher memory ceiling | Allowed, listed by field and setting name, then recreated |
| Any other existing setting, issuer, `KC_*` (except the exact private admin hostname below) or `*_FILE` setting, a removed mount other than the accepted dashboard override, a lower ceiling, a named volume, a secret, network, service, top-level section, user, entrypoint, health check, logging, tmpfs, capability, PID or CPU change | Refused |
| Any change to PostgreSQL, or an observability image | Refused: outside this procedure |
| A different runtime-input inventory, or a realm import change outside the migration below | Refused until a reviewed change covers it |

The result must keep `restart: unless-stopped` on persistent services, `restart: no` on the
one-shot actor, the accepted localizer ceiling and Grafana plugin policy, the mounted
localization dashboard, the same image for migration and application, zero publications, and
scheduling, Telegram and the [account-deletion overlay](#optional-automatic-account-deletion)
off. Installation run and configuration labels survive unchanged on every service; a new label
names the upgrade on recreated ones.

**Refusals before any change.** No recorded completed installation and transition, drifted live
containers, an unfinished upgrade, an unreviewed plan hash, a staged bundle differing from it,
an unsupported Docker version, an unsynchronized clock, insufficient memory or disk, invalid
TLS, secret or model inputs, or a truststore lacking the target JVM's anchors or the private
CA. An applied migration that differs from the target also refuses, as do a failed Flyway row,
a database ahead of the target and an out-of-order pending migration, or a target that would
replace the accepted dashboard with an older copy. The plan also proves the Keycloak administrator
credential while Keycloak runs, because the realm migration needs it only after Flyway.

**Recovery point.** A manual `pg_dump` never qualifies. Apply requires one
[canonical pair](#paired-backup-and-recovery-candidate) under 24 hours old, captured from the
active configuration with its images, whose ciphertext verifies, plus a receipt of
its off-host copy (`recovery pull` on the destination, or `publish`) and of an isolated restore
of that same pair. The two receipts are owner-supplied attestations that the host cannot
independently prove. Writers must still be closed by that backup: its journal phase and
pair identity must match, roles must be `NOLOGIN` and drained, and the schema identities
must equal the manifest. A thaw in between therefore needs a new pair, and `--resume`, `--abandon`
and a forward fix check the same continuity: a stale fence is never recreated over writes the pair
did not capture. Recorded synchronization or unexpired translation claims refuse too; resolve them
before fencing.

**Sequence.** Everything runs under the recovery lease and the identity fence, so backup,
restore, thaw and identity maintenance refuse throughout and after an interruption.

1. Claim the journal and replace the backup's journal phase, so `recovery thaw` now refuses.
2. Run the **target image's** one-shot actor, then verify the history against the target. Only its
   role can log in, for this step alone. The gate **stays closed**: the reviewed wrapper refuses a
   closed gate, so this tool, holding the lease and the identity fence, runs the program that wrapper
   guards. Every other start of the actor is still refused.
3. Re-apply the target's monitoring reader grants. Existing reader settings are kept and rechecked.
4. Allow only Keycloak's role, start the target Keycloak, and reconcile the realm.
5. Replace the superseded edge, application, localizer and Grafana containers with stopped target
   ones, and name the target in `upgrades/ACTIVE.json`. No superseded container remains to start.
6. Reopen roles and gate, release the identity fence, then start monitoring, the edge and the
   application in that order. The edge precedes the application because the BFF validates its issuer
   through it. The localizer and Grafana start last.
7. Smoke source, version, health, private TLS, denied routes, the issuer contract, an
   anonymous personal-route refusal and a catalogue read, plus monitoring, reader and row counts.

**Optional services.** The localizer and Grafana stay [optional for product readiness](#post-acceptance-runtime-transition):
either may be stopped before the upgrade, and the upgrade starts both, as the transition does. If one
does not become healthy, the journal and `--verify` report it in `optionalDegraded` and the product is
not fenced for it. A failed required service, or a cancellation, fences the runtime.

**Existing realm.** Keycloak's startup import skips an existing realm, so a new image does not
migrate it. The tool runs the shared
[`--sessions-only` alignment](#remember-me-provider-compatibility) on the private identity
network, with the administrator password crossing stdin, never an import. It changes only the
five session settings and the BFF Remember Me mapper, and snapshots the pre-change values.
The private-admin alignment separately handles the approved master frontend attribute.

**Registration notice extension (#276): prepared locally, not deployed or accepted.**
The upgrade also admits exactly the old-to-approved `localizationTexts.es.termsText`
change from the legal publication (#434), identified by fixed source hashes in
[`production_registration_notice.py`](../../scripts/production_registration_notice.py).
Every other import difference still refuses. The target publication must also contain
the exact notice actor matching the reviewed operator; planning refuses an older or
incompatible target before any live realm query. The plan identifies this notice migration
explicitly. A new startup import alone cannot update the existing realm.

The notice actor uses the [Keycloak localization API](https://www.keycloak.org/docs-api/latest/rest-api/index.html)
to change only that Spanish key to the approved text with canonical product links.
It snapshots all locale overrides before PUT, preserves the original snapshot across
interruptions, accepts only the known old or already-aligned live notice, and verifies
all other texts and the realm representation on apply, check and resume. The five
reviewed session fields are verified independently by the session actor and SQL
postconditions, so its alignment does not invalidate the notice snapshot. Metadata work
is bounded to 32 locales, 2,048 texts per locale and a 1 MiB localization inventory;
exceeding a bound refuses before mutation. Each metadata HTTP response is limited
before JSON decoding. No account listing is involved.

Account, credential, role, group, identity-link and all other configuration SQL digests
remain unchanged. The aggregate localization digest may change only when the notice
actor's complete multilingual snapshot comparison succeeds; a changed sibling text,
another locale or an unrelated realm setting keeps the recovery fence closed. The
change remains under the existing upgrade lease and writer fence. No full realm import,
client replacement, public registration activation or unrelated policy alignment occurs.
Forward fixes retain the original protected notice snapshot before claiming the new
upgrade. A missing snapshot after localization changed, or an invalid or conflicting
snapshot, refuses before superseding the failed upgrade; repeated fixes never adopt
the changed state as a new baseline.

Local tests cover the exact source delta, unknown notices, read-only checking,
repeated execution, lost PUT acknowledgement, snapshot preservation, metadata bounds
and unrelated drift. The focused disposable proof uses real Keycloak with synthetic state and no published
ports; it does not qualify production PostgreSQL, the whole upgrade or the VPS:

```bash
python3 scripts/test-private-production-registration-notice-docker.py \
  --keycloak-image "$REVIEWED_KEYCLOAK_IMAGE"
```

It exercises a real PUT, a lost acknowledgement, session alignment, resume, repeat,
read-only check and unrelated-drift refusal. It uses locally available immutable
images only. A fresh reviewed trusted-main publication and real-host upgrade
qualification remain required before operational acceptance. The older published
operator still refuses targets containing the new notice; do not bypass its check.

The [private maintenance plan](../../docs/development/operations-runbook.md#private-production-maintenance-plan)
groups owner authorizations and recovery boundaries for the installed runtime.


**Interruption.** `upgrades/<sha>/upgrade.json` records the phase durably, with sanitized
reasons, before each step. A handled failure fences again, stops the writers and records whether
containment was confirmed. Every step checks its own postcondition, so `--resume` repeats
them from the canonical fence: Flyway has nothing pending, and the realm is already aligned.
`--resume` and `--abandon` verify the operator bytes against the claimed fingerprint offline, so a
GitHub outage cannot strand them; a newer operator revision must prove its publication first.

| Stopped at | State | Exit |
|---|---|---|
| Before any change | Nothing claimed; the backup fence still stands | Fix the cause and retry, or `recovery thaw` |
| Claimed, schema and identity unchanged | Upgrade fence; thaw refuses | `--resume`, or `--abandon` then thaw |
| Flyway ran or Keycloak started | Same, and an older image is no longer assumed safe | `--resume`, then `--forward-fix-of`, or a paired restore |
| After reopening | Re-fenced on a handled failure. After `SIGKILL` or host loss the gate stays open on target containers | `--inspect`, then `--resume` or `--forward-fix-of` |
| Migration actor still running, or a failed Flyway row | Resume refuses; no second actor starts | Wait and inspect, or forward fix or paired restore; never `flyway repair` by hand |

**Abrupt termination.** `SIGKILL`, a daemon restart or host loss cannot run any cleanup; an observable
session loss or `SIGTERM` does fence again. Safety before reopening therefore rests only on durable
state: the closed gate, the `PENDING.json` phase, the identity fence and PostgreSQL `NOLOGIN`.

| Attempt while an upgrade is pending | Result |
|---|---|
| Daemon restart or abrupt loss | Stopped writers stay stopped, a never-started container stays down, one-shot actors do not rerun, and gate, fence and role state persist. PostgreSQL rolls back an in-flight transaction |
| Start of the superseded or target application | Its wrapper refuses before reading a secret |
| Start of either migration actor by its normal entrypoint | Its wrapper refuses |
| Start of the superseded Keycloak | Its role is `NOLOGIN`, so it cannot reach its database. Once the target Keycloak is running, it is private and the edge still refuses |
| Start of the edge | Both hosts answer maintenance |
| `recovery backup`, `thaw`, `reopen`, identity maintenance | Refused by the fence or the phase |

An orphaned actor may finish its migration after the controller died, which `--resume` then finds
applied. Only the migrator role can log in during that window. After reopening there is deliberately
no fence: only target containers remain, and starting a service from a superseded file is the one
remaining mistake, which [root is always able to make](#paired-backup-and-recovery-candidate).

Forward-only limits: migrations are never reversed and no older image is started after they
ran. Returning to the pair means a separately authorized
[paired restore on an empty target](#restoration-and-deletion-gate) with the pair's image set,
never over live volumes, and it loses any writes made after reopening. Previous digests and
configuration stay in the journal and the old bundle is kept; no image or bundle is deleted.

**After an upgrade.** Use the `configurationFile` in `upgrades/ACTIVE.json` for any later Compose
command and for `recovery --config`, with `recovery` from the same bundle. Older files would
revert images when they recreate a service. The file is named from just before reopening.

**Deliberate trade-offs.**

- *The migration actor runs around its wrapper, not behind an open gate.* Opening the gate for it left
  a durable open gate after any abrupt death. The exception is one reviewed entrypoint, only under this
  tool's own lease and fence; every other start keeps the guard.
- *The backup's fence stays closed through the upgrade.* The pair is then exactly the
  pre-upgrade state, at the cost of downtime that includes the off-host copy and restore check.
- *A restore receipt is mandatory.* It lengthens that window, but a checksum alone does not show
  that a pair restores before a forward-only change.
- *The reviewed scope is narrow.* PostgreSQL, observability images, realm changes beyond the
  session migration and new runtime inputs refuse, and each needs a reviewed extension of this
  tool. This is cheaper than a generic deployment engine for one private host.
- *Reopening precedes the smoke*, because the BFF needs the edge and identity to start. A failed
  smoke re-fences, but writes in that short private window are newer than the pair.
- *Local image identity is preferred* when a digest is already pulled: it is content-addressed, so
  a registry outage or rate limit does not block a plan. CI and security evidence stays mandatory.

**Local evidence.** `scripts/test-private-production-upgrade.py` covers the delta, evidence, continuity
and phase matrices with a fake host, and the exact first pair's rendered delta.
`scripts/rehearse-private-production-upgrade.py` runs real images on disposable PostgreSQL, Keycloak,
edge, application and monitoring with synthetic accounts: a real paired backup and isolated restore,
real `SIGKILL` of the controller with the actor in flight, with the realm half migrated and after
reopening, a contained `SIGTERM`, every start in the table above, and preservation of identity, rows,
reader settings, unchanged containers and provenance. `scripts/test-private-production-restart-docker.py`
proves the daemon rows on a disposable real daemon.

They do **not** cover: a host reboot, the VPS's Docker pair or real volumes; catalogue-scale
migration duration; node-exporter, which Docker Desktop cannot mount; localization, because no model is
supplied and the localizer is only exercised as a failing optional service; a real off-host copy
(simulated receipt); operational publication, which needs a merged revision; or the installer's input
contract against real protected paths, which has a contract test only. The rehearsal also accepts
Docker Desktop's spelling of directory binds and skips the Docker data-root disk check.

### Private-production administration

**Repository implementation (#464); not deployed or accepted on the VPS.** The
[platform design](../../docs/architecture/deployment/mvp-platform-and-delivery.md#private-production-administration)
owns the boundary and [ADR-0035](../../docs/decisions/0035-separate-private-production-admin-ingress.md)
records the master's frontend exception approved by the owner. The earlier manual
Grafana success used a temporary bridge; it proves neither persistence nor Keycloak,
ACL, reboot or public-network acceptance.

[`private_production_admin.py`](../../scripts/private_production_admin.py) owns the two
endpoint/bridge ports, exact settings and systemd units. It installs only the dedicated
admin units and Serve handlers, refuses conflicts/foreground Serve/Funnel, and verifies
that unrelated Serve state (especially TCP 443) is unchanged. The bridges use Caddy's
existing fixed peer and Caddy uses Docker service names. An installation probe checks
actual host-to-edge connectivity through both loopback backends before enabling Serve.
Only the host's Docker gateway source is admitted on those listeners; attached application
peers cannot use them, even with spoofed forwarding headers. No Docker publication, image,
data volume, issuer, BFF setting or existing product bridge
is changed by this helper. It needs existing `/usr/bin/socat`, Tailscale and systemd.

#### Prepare the owner-only access policy

Before any live command, obtain separate owner authorization for the rollout. Review the
**effective** policy in Tailscale, including every additive ACL and grant: remove or narrow
any wildcard/member/group/tag rule that also grants the admin ports. Preserve existing
SSH/product access deliberately. Merge
[`admin-policy.hujson.example`](admin-policy.hujson.example), substituting the actual
owner login, non-owner/deployment identities and this VPS's tailnet IPv4 and IPv6 addresses
outside Git. Run the policy's accept/deny tests before saving. Test both ports with the
owner, a real non-owner device and the deployment identity. A tagged node must not inherit
the owner's user access. Where no non-owner test device exists, acceptance remains pending.

Keep a protected copy of the effective saved policy and its SHA256 plus the owner decision
reference. `--policy-sha256` records an acknowledgement; it cannot prove control-plane
policy or replace negative network tests. This implementation does not change ACLs.
See [Tailscale grants](https://tailscale.com/docs/features/grants) and
[Serve](https://tailscale.com/docs/reference/tailscale-cli/serve).

#### Authorized rollout

1. Publish only after owner review, then use the normal
   [in-place upgrade](#in-place-upgrade-of-the-installed-runtime) plan/stage/apply sequence
   with current trusted-main publications, the new operational fingerprint, a fresh
   canonical paired backup, off-host and isolated-restore receipts, and the owner reference.
   Do not edit `compose.target.json` or the installed runtime input. Review the plan's
   `adminIngress`, environment delta, Caddy bytes and master migration explicitly.
2. The private adapter sets the external Grafana root and Secure cookies and adds only
   `KC_HOSTNAME_ADMIN`. The upgrade's identity phase uses
   [`configure-keycloak-admin`](bin/configure-keycloak-admin) to snapshot the original
   master's frontend attribute to `upgrades/<target>/master-frontend.json`, then changes
   only that effective setting and verifies the realm representation. Keycloak can
   materialize typed defaults as equivalent string attributes; the verifier accepts only
   known aliases equal to their unchanged typed values. It reauthenticates after changing
   master's issuer; existing administrative tokens require a new login. Product tokens
   and sessions keep their issuer. Unexpected existing URLs
   refuse. Session policy alignment and the existing product-state digest checks remain.
   A failure keeps the recovery fence closed; use the existing inspect/resume/forward-fix
   procedure. No manual `thaw` or identity changes are required.
3. After the upgrade's `--verify`, inspect `tailscale serve status --json`,
   `tailscale funnel status --json`, `ss -ltnp` and the existing systemd product bridge.
   Preserve TCP 443 and its backend. Reconcile the temporary Grafana route: if it is the
   exact proposed handler the helper can retain it, otherwise remove **only** its reviewed
   handler. Identify and stop the exact temporary `socat` PID/session listening on the
   Grafana loopback port before installing the permanent unit. Never kill all socat
   processes or use `tailscale serve reset`. The helper refuses a busy unowned loopback
   port or a conflicting handler; it does not silently adopt the temporary process.
4. Use the protected, root-owned, published operational checkout used for the upgrade;
   keep that path available across reboot. Replace these review placeholders outside Git:

   ```bash
   admin=/srv/gameometro/operations/REVIEWED_SHA/scripts/private_production_admin.py
   python3 "$admin" --install \
     --operational-revision REVIEWED_SHA --adapter-fingerprint REVIEWED_FINGERPRINT \
     --owner-reference OWNER_DECISION --policy-sha256 EFFECTIVE_POLICY_SHA256
   python3 "$admin" --verify
   ```

   `--install` is idempotent for the same source and units. It records the policy hash and
   decision, enables both hardened bridges, probes them, installs only the two background
   Serve HTTPS handlers and enables boot reconciliation. On interruption, rerun the same
   reviewed command; no application state is changed. Do not move/delete the operational
   checkout. A different unit definition requires reviewed withdrawal/reinstallation.

#### Acceptance on the authorized VPS

Record evidence in the protected acceptance location, with no passwords, tokens, cookies
or personal data in transcripts. Run the helper's `--verify`, compare Serve before/after,
and inspect unit status plus host IPv4/IPv6 listeners. From an owner device open Grafana
at `https://gameometro-prod.tailca0b11.ts.net:8443/` and Keycloak at
`https://gameometro-prod.tailca0b11.ts.net:10000/admin/`. Require trusted HTTPS, native
login/logout, Secure/HttpOnly session cookies, correct redirects, Grafana dashboards and
Keycloak realm navigation/Admin REST. Confirm unauthenticated APIs refuse access.

From non-owner and deployment identities, deny **both** ports using the effective policy
and real device probes on tailnet IPv4/IPv6. From an independent external network verify
that host public IPv4/IPv6 and LAN paths do not expose either admin origin/backend and
Funnel is absent; unavailable address families are an explicit evidence limitation.
Using the canonical public identity host, require 404 for `/admin`, `/admin/realms`,
`/admin/master/console/`, `/realms/master` and its discovery/token paths, also with spoofed
Host and `Forwarded`/`X-Forwarded-*` values (a mismatched TLS SNI/Host may yield 421).
Verify the exact product issuer and normal Gameómetro browsing, registration, login,
logout and Remember Me through the unchanged product TCP 443 route. Recheck healthy
services, dashboards, datasources, metrics history and data counts.

In the same authorized maintenance window, reboot the host and verify bridge units,
Serve handlers and both browser sessions again. Through an authorized operator rollout,
recreate Grafana/Keycloak and verify that their dynamic address changes require no host
edits. Recovery/identity fences must close the private routes as well. A local Docker
restart test is not real-host reboot evidence. Keep #464 open until those acceptance
steps pass; this work does not close #276 or authorize public exposure.

#### Withdraw only administration ingress

Use the **same installed operational source**, even if a later upgrade is active:

```bash
python3 "$admin" --remove --owner-reference OWNER_WITHDRAWAL_DECISION
```

Removal refuses drift/conflicts, removes only the two owned Serve routes, disables and
removes only their three systemd units and receipt, and leaves TCP 443 untouched. Repeated
removal with no remaining owned units/routes is a no-op. Recheck Serve, listeners and
Gameómetro/OIDC. This restores the previous access boundary without restarting services,
restoring a database, deleting volumes or downgrading an image. Runtime admin settings and
master's private frontend remain dormant. Direct private operator access remains available;
restoring the old frontend attribute would require a separately reviewed forward change
under the upgrade lease using the saved snapshot, or the existing paired restore on an
empty target. Never run an old Keycloak image against the advanced live database.

The focused contracts are `scripts/test-private-production-admin.py`; the opt-in
`scripts/test-private-production-admin-docker.py --help` exercise uses disposable real
Caddy/Keycloak/Grafana without host publications. Neither touches Tailscale or the VPS.
Application/backend/frontend/OpenAPI artefacts and their versions are unchanged; this is
an operational capability delivered by separately fingerprinted source and runtime bundles.

### Before any real user data

A passing private install is **not** a recoverable or publicly accepted service.
Before real accounts/ratings, complete and accept the
[independent recovery policy](../../docs/architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery):
paired encrypted application/identity captures under deny/drain; direct OVHcloud
uploads with least privilege and tested Object Lock/version/expiry semantics within
the unchanged retention ceiling; independent integrity/missed-run and consumption
alerts; separately retained reconstruction secrets and off-VPS decryption/signing
keys; synchronous independent ledger/archive durability before destructive mutations;
and cloud-only loss-of-host rebuild/restore with Lenovo unavailable. Prove
principal/rating consistency, current deletion replay, old-session revocation,
fenced reopening and achieved recovery objectives on the actual target. Local dumps,
checksums or #275's synthetic restore cannot satisfy those gates. Retention decisions
and provider tests remain with the [storage investigation](../../docs/research/public-production-backup-storage.md).
Public release additionally needs its own complete qualification and owner go/no-go.

## Host bootstrap and operating handoff to #276

**Prepared sequence; real-host steps are not exercised or authorized.** The
[first-install procedure](#private-first-installation-and-validation) owns the executable
private qualification path; the remainder below is the wider #276 handoff. Use the
[release gates](../../docs/development/delivery-lifecycle.md#public-production-gates)
for purchase/exposure decisions and the [candidate audit](../../docs/research/public-production-candidate-acceptance.md)
for exact evidence and remaining work. The current deployment command unconditionally
refuses real-host operation; it is not the new first-install adapter.

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
5. Implement and review automated paired-backup/direct-S3 and independent verification
   schedules against the
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

## Next private implementation stages

**Private adapter implemented for review; host execution remains unauthorized.** Host
foundation does not approve a product release. Start from the
[signed #275 candidate](../../docs/research/public-production-candidate-acceptance.md#owner-acceptance-record)
and preserve its identities and receipts. Do not repeat its accepted local campaign.
The staged checkout is an administration source, not a production selection. Compare
its runtime inventory, identity implementation, migration inventory and publications
with the signed candidate before selecting any image. Reusing the accepted images
requires matching reviewed operational sources; newer realm/wrappers must not be
mounted into older images by convenience. Changed runtime sources need a separate
reviewed rollout, as the deployment verifier already requires.

| Stage | Concrete deliverable | Evidence or approval before the next action |
|---|---|---|
| 1. Read-only preflight | Host inventory, candidate comparison and a sanitized refusal report; no Docker mutation | Retain recovered NTP in the host record; qualify privileged Docker inventory, effective firewall/SSH and protected inputs |
| 2. Private adapter preparation | Implemented preparer, exact private rendering, ordered first installer and stop-only interrupted-attempt reconciliation; regression and disposable Docker proofs | Owner reviews the implementation, exact candidate/digests, fingerprints, private TLS strategy and fresh-state bootstrap procedure; execution remains disabled until prerequisites pass |
| 3. Cloud recovery preparation | Bounded S3 transport for the existing encrypted pair, independent observer and durable ledger/archive copy adapters | Retention decisions, provider setup authorization and effective cloud permissions/semantics; no bucket or credential is provisioned by repository work |
| 4. Authorized private qualification | Explicit fresh-host bootstrap, migration/activation, synthetic journeys, paired backup and cloud-only reconstruction with deletion replay | Separate authorization to run the reviewed procedure; failures preserve closed gates, journals and receipts |
| 5. Exposure decision | Real-host capacity, privacy/relay/provider, network/abuse, observability and recovery acceptance | Separate owner public go/no-go under the delivery lifecycle; private qualification alone grants no public access |

Stage 2 now has the executable [first-install procedure](#private-first-installation-and-validation),
which owns its preflight, trust binding, private TLS, interruption and recovery rules.
The accepted deployment command remains rehearsal-only; its guards are unchanged.
Stage 3 remains proposed and is not supplied by this adapter. Neither a successful
private preparation nor the owner-reported recovered NTP authorizes host execution,
public exposure or storage of real user data.

### Local evidence and review limits

`test-private-production-preparation.py`, `test-private-production-installation.py`
and `test-private-production-control.py` cover rendering/trust and simulated privileged
host/storage failures. They do not establish VPS behavior. The manually invoked
`test-private-production-control-docker.py --evidence /new/protected/report.json`
uses real Docker for disposable synthetic containers, private bridges and scratch
volumes: retained kernel lease on actor death, competing lease refusal, owned-ID
stop, SIGHUP/EOF/PTY teardown, hard-kill fencing and explicit stop-only recovery,
effective isolation checks, and the accepted JVM's truststore behavior. It starts
no Gameómetro service, publishes no ports and cleans only its randomly labelled
fixtures. Its protected receipt records source hashes, actual versions, failed or
inconclusive checks and the absence of VPS/application deployment. Any intermediate
failed campaign remains evidence; a later changed-source run does not erase it.

Local Docker mechanism evidence does not qualify the target Engine/Compose pair,
actual systemd configuration, SSH/provider packet paths, complete BFF login/token
exchange, host-loss recovery or S3. Those remain separately authorized work after
this second review. Product/image version remains the signed candidate's version;
these operational changes have a separate reviewed fingerprint and no product API,
MVP or migration change.

### Cloud transport dependency

The [S3 preparation guide](#s3-transport-preparation-276) owns the locally prepared
adapter and its inputs. The [evidence boundary](../../docs/research/public-production-backup-storage.md#local-s3-adapter-evidence)
distinguishes demonstrated local guarantees from missing provider, deletion,
ledger/archive and scheduler acceptance. Install no [production timer](#scheduled-backup-cycle-276)
until those gates and cloud-only recovery have passed. Credentials remain outside
application containers; Lenovo must be unavailable for the cloud-only proof.


## Optional automatic account deletion

The prepared `compose.account-deletion.yaml` overlay is opt-in and is not consumed by the
current promotion command. It exposes no management port and passes no Keycloak credentials
to the application. Before activating it, qualify the existing #274 independent evidence/key
boundary, protected executor availability/channel and recovery under #276. The
[runbook](../../docs/development/operations-runbook.md#protected-executor-configuration)
owns configuration, the dedicated capability secret, rotation, runner supervision and pending
request recovery. File-backed Compose secrets must be privately readable by runtime uid 10001;
never commit capability material. This source preparation is not deployment or acceptance.
