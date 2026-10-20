# Public-production runtime configuration

- **Status:** Prepared runtime candidate (#275 increments 1 and 2), pending real-host validation; never deployed

This directory holds the versioned runtime configuration for the future public
production host. No host, DNS record, certificate, deployment command, backup or
telemetry stack exists for it. [ADR-0025](../../docs/decisions/0025-host-public-production-on-a-deferred-single-vps.md)
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
| [`bin/run-keycloak`](bin/run-keycloak) | Keycloak entrypoint that reads secret files and refuses empty inputs |
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

These hold for the first start on any host and are not yet automated:

- **Migrations before the application.** The application never migrates at startup and
  sits behind the `application` profile; the one-shot `migration` actor sits behind
  `deployment` and runs the same digest. A deployment starts PostgreSQL, runs
  `migration` to successful completion, and only then starts the application; a failed
  migration leaves the previous application running or stopped, never activated.
- **Keycloak memory.** The 768 MiB ceiling is the envelope hypothesis, half of
  private-dev's; Keycloak has never run within it. Prove it under the realm import and
  representative sign-in load before accepting it.
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

This is configuration evidence. It does not start the application, Keycloak,
PostgreSQL or the localizer from this topology, and it proves nothing about a real host.

## Not yet validated

These need later increments or the real host (#276):

- Public ACME issuance and renewal, DNS, IPv4 and IPv6 exposure, host firewall and
  Docker NAT. Docker's userland proxy presents IPv6 clients with an internal source
  address, which matters before any per-source limit or diagnostic relies on it.
- Starting the full topology: realm import through `bin/run-keycloak`, the BFF
  reaching the issuer through the edge with the public certificate, PostgreSQL
  secret-group access and every resource ceiling.
- Pulling and running the published Keycloak and localizer digests on the host.
- Deployment and migration commands, smoke and receipts, backups and restore,
  telemetry, the owner's Keycloak administration path, and the
  [canonical issuer check](../../docs/architecture/deployment/mvp-platform-and-delivery.md#canonical-issuer-contract)
  with the production names.
- Per-source and aggregate request-rate limits, which stock Caddy does not provide;
  the edge's body, header and timeout bounds are uncalibrated.
