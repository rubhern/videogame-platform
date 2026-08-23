# Walking-skeleton compatibility evidence

- **Status:** PASS
- **Gate closed:** 2026-08-23
- **Authority:** [Technology baseline compatibility gate](../architecture/technology/mvp-technology-baseline.md#16-walking-skeleton-compatibility-gate)
- **Hosted evidence revision:** `e138f54ebc529ff53207bd520e949447e344fc89`
- **Hosted evidence:** [trusted `main` walking-skeleton run 32661542668](https://github.com/rubhern/videogame-platform/actions/runs/32661542668)
- **Immutable application image:** `ghcr.io/rubhern/videogame-platform@sha256:60905af2cdad62afe16ebce65f4c98cd4b253d6d1f61ef44eba6a17359b67260`

This is the concise, reproducible evidence record for issue #34. Run
`git rev-parse HEAD` with any command below to identify the exact checked-out
revision. The hosted revision above is the trusted complete-`main` evidence anchor;
the issue #34 closure change adds the dependency-manifest and resource-budget checks
to the same existing image job rather than duplicating the workflow.

## Evidence matrix

| Evidence | Result | How to reproduce | CI/local |
|---|---|---|---|
| Java 25 without preview features | PASS | `./mvnw --version && ./mvnw clean verify`; Maven Enforcer and compiler configuration require Java 25 and `enablePreview=false` | Local; `Java backend, architecture and PostgreSQL integration` |
| Spring Boot 4.1 + Spring Modulith 2.1 and application startup | PASS | `./mvnw -pl backend -Dtest=BackendStartupTest,ModularityTest test` | Local; backend job |
| PostgreSQL 18 + Flyway from zero | PASS | `bash scripts/validate-migrations.sh` | Local; `Fresh PostgreSQL 18 migration` |
| PostgreSQL Testcontainers persistence | PASS | `./mvnw -pl backend -Dtest=CataloguePersistenceIntegrationTest test` | Local; backend and migration jobs |
| OpenAPI 3.1.2 to TypeScript, including date `oneOf` | PASS | `npm ci && npm run frontend:generate-api && npm run frontend:test -- release-date.test.ts` | Local; `Frontend static, type, component and build checks` |
| TypeScript strict `tsc --noEmit` | PASS | `npm run frontend:typecheck` | Local; frontend job |
| Same-origin product-facing typed API | PASS | `npm run frontend:verify && bash scripts/validate-browser.sh` | Local; frontend and packaged-browser jobs |
| Keycloak 26.7 OIDC BFF session with server-side tokens | PASS | `bash scripts/validate-identity.sh` | Local; `Real Keycloak 26.7 OIDC BFF compatibility` |
| Liveness, readiness, structured logs, metrics and trace/correlation context | PASS | `./mvnw -pl backend -Dtest=BackendStartupTest test` | Local; backend job and both image runtime probes |
| `linux/amd64` application image | PASS | `bash scripts/validate-container-image.sh` | Local; `Multi-architecture application image` |
| `linux/arm64` application image and emulated startup | PASS | `bash scripts/validate-container-image.sh` | Local with registered QEMU when needed; image job |
| PostgreSQL 18 ARM64 manifest | PASS | `bash scripts/local-dependencies.sh verify-images` | Local; image job |
| Keycloak 26.7 ARM64 manifest | PASS | `bash scripts/local-dependencies.sh verify-images` | Local; image job |
| Complete-topology CPU/RAM budget | PASS | `bash scripts/validate-topology-budget.sh` | Local; image job |
| CI reproduction | PASS | `bash scripts/validate-actions.sh`; inspect the trusted run linked above | Local workflow lint plus trusted `main` CI |

`BackendStartupTest` starts the structured-log profile against PostgreSQL 18 and
asserts the application context, liveness/readiness groups, safe build metadata,
bounded meters, ECS JSON access log, `X-Correlation-ID`, W3C `traceparent`, and
telemetry safety. `validate-browser.sh` proves that React calls the real local
PostgreSQL-backed API through the same-origin typed product boundary. The identity
test uses real Keycloak and verifies an opaque HttpOnly session, absence of OAuth
tokens and browser storage, CSRF rejection, logout and session invalidation.

The image gate builds one OCI index for `linux/amd64` and `linux/arm64`, starts and
probes both images, and uses explicit ARM64 QEMU on AMD64 CI. It also checks non-root
execution, read-only runtime constraints, source metadata, Trivy results and
CycloneDX SBOMs. The hosted run published that exact scanned index by commit SHA and
verified the preserved digest shown above.

## Resource budget

The resource check renders the `full` Compose profile from the committed non-secret
example configuration and fails if a required service lacks a positive CPU or memory
limit, or if the sum exceeds the approved OCI ceiling.

| Runtime service | CPU limit | Memory limit |
|---|---:|---:|
| Application: frontend + BFF/API + modular monolith | 0.5 | 1 GiB |
| PostgreSQL: application and Keycloak databases | 0.5 | 0.5 GiB |
| Keycloak | 1.0 | 1 GiB |
| **Configured total** | **2.0 OCPU** | **2.5 GiB** |
| **Approved maximum** | **2.0 OCPU** | **12 GiB** |
| **Memory headroom** | — | **9.5 GiB** |

CPU limits are scheduling quotas rather than reservations. The host OS, container
runtime and future private-network agent share the two available CPUs and retain the
documented memory headroom. Buildx, QEMU, Playwright and Testcontainers are ephemeral
CI/development evidence tools, not persistent target-runtime components. OTLP export
remains disabled and no collector is required to demonstrate this walking skeleton;
selecting and budgeting a deployed telemetry path remains part of the later private
`dev` platform work. This is compatibility evidence, not capacity planning.

## Gate decision

**PASS.** Every issue #34 criterion has repeatable executable evidence, the approved
version lines and architecture remain unchanged, and the initial runtime limits fit
the 2 OCPU / 12 GB OCI Ampere A1 ceiling. No failure required classification or a
baseline fallback. If a future run fails, classify it first as configuration, patch
compatibility, emulation, image manifest, dependency, or optional-library failure and
reopen the gate instead of weakening Java, Spring, PostgreSQL, Keycloak or ARM64.

This closure adds validation and documentation only. It changes no releasable
backend, frontend, OpenAPI, root npm tooling or IGDB PoC behaviour, so none of those
artefact versions is incremented.
