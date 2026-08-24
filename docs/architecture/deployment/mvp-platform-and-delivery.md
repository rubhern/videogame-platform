# Learning MVP platform and delivery design

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Private, zero-recurring-cost learning platform

This document owns environment purpose, deployment topology, artefact/configuration/
secret behaviour, migration/backup/recovery, and technical delivery. The
[delivery lifecycle](../../development/delivery-lifecycle.md) owns human workflow,
gates, review, acceptance, and release.

## Environments and topology

| Environment | Purpose | Data/access |
|---|---|---|
| `local` | Development and focused proofs on supported WSL2 | Disposable/seeded; loopback only |
| `test` | Per-job automated evidence | Generated fixtures; CI only |
| `dev` | Persistent private integration, HTTPS, identity, delivery, telemetry, recovery | Non-sensitive learning data; owner tailnet only |
| `production` | Deferred/prohibited | Undefined |

The `dev` target is one OCI Always Free Ampere A1 VM hosting the non-root application
container, Keycloak, PostgreSQL, and bounded telemetry. Tailscale provides private
HTTPS/admin access; it does not replace Keycloak/product authorization. No public
application, identity, database, telemetry, or SSH ingress is allowed.

Remote infrastructure has not been provisioned. It may be created only after
rechecking current zero-cost eligibility and using reviewed Terraform. If an eligible
free resource is unavailable, use local `dev` or wait; never silently select paid or
trial-only resources. Public production, HA, staging, Kubernetes, distributed
components, automatic broad sync, and paid managed services remain deferred.

## Artefact and delivery

One immutable multi-architecture OCI image contains the compiled frontend, BFF/API,
and modular monolith. It runs non-root, contains no environment configuration,
secrets, raw provider data, personal data, dev seed, or copied provider images, and
is identified by commit SHA and content digest rather than `latest`.

GitHub Actions validates pull requests. Trusted `main` builds/scans the same index,
produces SBOM/provenance evidence, and publishes to GHCR. Pull requests receive no
provider/deployment secrets and never publish/deploy. Deployment promotes an already
validated digest through a protected manual `dev` boundary.

Technical sequence:

```text
select digest -> validate free target/secrets/recovery -> serialized migrations
-> replace application -> readiness -> smoke/accessibility -> accept or recover
```

Concurrent deployments are prohibited. A failed new version is not successful merely
because the old one remains healthy. Deployment success is not product acceptance.

## Configuration and secrets

Configuration is injected at runtime; `.env.example` files document local names and
safe defaults. Missing security-critical configuration fails clearly. Remote secrets
use a protected source such as OCI Vault and are independently rotatable and
least-privileged. Secret values stay out of Git, images, Terraform state where
possible, frontend code, URLs, logs, screenshots, and CI artifacts.

## PostgreSQL, migrations, and recovery

One server hosts separate application and Keycloak databases/roles. Business modules
retain logical table ownership. A dedicated actor runs immutable forward Flyway
migrations before application replacement. Destructive changes use expand/contract
and explicit recovery; application rollback is allowed only while schema compatible.

Back up irreplaceable ratings, identity mapping/configuration, and product curation
outside the VM, encrypted and within the free limit. Record environment, time,
PostgreSQL/schema/application version; retain only useful backups; prove isolated
restore after setup and material changes. Catalogue provider data may be resynced,
but personal/identity/curation state is not assumed disposable.

## Health, observability, privacy, and failure

Liveness reports process viability. Readiness proves supported local-data behaviour
and required local dependencies; IGDB/CDN/telemetry outages do not make the app
unready. Health never reveals topology or secrets. Telemetry uses bounded labels,
replaceable OpenTelemetry-compatible export, minimal retention, and no personal data
or credentials.

| Failure | Required behaviour |
|---|---|
| IGDB unavailable/rate-limited | Continue local reads; sync records failure |
| Cover CDN unavailable | Use product fallback; keep game visible |
| No valid catalogue | `CATALOGUE_NOT_READY`; no request-path provider call |
| Migration failure | Do not activate new application |
| Readiness/smoke failure | Keep/redeploy prior compatible image or forward-fix |
| Backup failure | Report recoverability failure; do not claim release success |
| VM loss/reclamation | Reprovision from IaC, restore durable state, verify journey |

Terraform, standard OCI containers, PostgreSQL logical backups, OpenTelemetry, and
private-ingress abstraction preserve portability. Revisit hosting when free policy,
capacity, reclamation, resources, Tailscale terms, or release mode changes.
