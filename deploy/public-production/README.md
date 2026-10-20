# Public-production runtime configuration

- **Status:** Prepared runtime candidate (#275 increments 1–4), pending real-host validation; never deployed

This directory holds the versioned runtime configuration for the future public
production host. No host, DNS record, public certificate, backup or telemetry stack exists for it.
A manual deployment command operates only within the disposable synthetic rehearsal;
real-host deployment remains blocked. [ADR-0025](../../docs/decisions/0025-host-public-production-on-a-deferred-single-vps.md)
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
| [`../../scripts/public_production_deployment.py`](../../scripts/public_production_deployment.py) | Manual deployment preflight, lock, migration, activation, smoke and receipts; rehearsal only |
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
model policy, using actual file bytes and executable modes. It must match the selected
image's trusted source inventory. The rendered synthetic configuration has a separate
fingerprint, checked before every attempt. A different runtime configuration requires
its own reviewed rollout; this command replaces only the application. Dependency
images must already be running at the approved identities and ready. Synthetic secret
file checks, TLS verification, production Compose validation and egress isolation
remain enforced. Remote Docker contexts are refused; the rehearsal requires a local
Docker Unix socket.

Each attempt takes a nonblocking exclusive lock for that disposable runtime before
reading state. An unrecorded application, interrupted/incomplete state, missing
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
or environment-variable override. Independent paired encrypted backups, isolated
restore/reconstruction and deletion replay, release/provider/privacy acceptance,
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
  owns where; a later increment proves it.

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
- Real-host deployment/migration/readiness/smoke and receipts, backups and restore,
  telemetry, the owner's Keycloak administration path, and the
  [canonical issuer check](../../docs/architecture/deployment/mvp-platform-and-delivery.md#canonical-issuer-contract)
  with the publicly served production certificate.
- Per-source and aggregate request-rate limits, which stock Caddy does not provide;
  the edge's body, header and timeout bounds are uncalibrated.
