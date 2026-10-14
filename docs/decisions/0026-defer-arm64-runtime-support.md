# ADR-0026: Defer ARM64 runtime support

- **Status:** Accepted
- **Date:** 2026-10-14
- **Owner:** Ruben Hernandez
- **Scope:** Supported runtime architecture and immutable application delivery
- **Amends:** [ADR-0008](0008-use-github-actions-and-ghcr-for-initial-delivery.md) and [ADR-0019](0019-host-private-dev-on-an-owner-managed-linux-host.md)

## Context

ARM64 support originated with OCI Ampere under superseded ADR-0005. Current private
operation uses an owner-managed AMD64 Linux host. OCI Ampere is outside the approved
infrastructure. Issue [#278](https://github.com/rubhern/videogame-platform/issues/278)
measures CI latency; the owner explicitly selected AMD64-only runtime support during
its implementation. Maintaining an unused architecture adds builds, emulation,
runtime checks and scans to the required feedback path.

## Decision

Support only `linux/amd64` for current runtime images and deployment. Defer ARM64;
it is not permanently rejected. Remove its mandatory image builds, emulation,
runtime verification, dependency-platform requirements, scans and SBOMs from CI.

Preserve non-root execution, required quality/security checks, image vulnerability
and secret scanning, SBOMs, source traceability and immutable digests. Runtime and
scanner checks consume the retained image archive; trusted `main` publishes that
same validated archive without rebuilding. PRs never publish or deploy. Parallel
independent checks remain required and fail closed through aggregation.

Before deploying to any ARM64 host (including OCI Ampere or AWS Graviton), restore
multi-architecture builds and the corresponding dependency, CI, runtime, migration,
security-scan, SBOM and exact-publication verification for every supported platform.
Validate that delivery on current source **before** an ARM64 deployment; host
selection alone does not satisfy this gate.

## Alternatives

- Retain multi-architecture delivery: deferred because no current deployment uses
  ARM64 and its mandatory checks consume CI time without validating the deployed host.
- Remove image/runtime/security checks: rejected; architecture scope changes do not
  weaken the guarantees for the supported runtime.

## Consequences and recovery

Existing AMD64 private-dev promotion and digest-based rollback remain compatible;
no host rollout or deployment is authorized by this change. ARM64 hosts cannot use
new releases until the restoration gate above passes. Historical multi-architecture
digests remain historical evidence, not current support promises.

CI optimizations are reversible by reverting the workflow/script change and
validating both required gates on the resulting source. Restoring ARM64 requires the
complete verification above, rather than changing the platform flag alone.

The backend/image version receives a pre-1.0 minor increment for the intentional
narrowing of supported operational compatibility. Frontend and OpenAPI contracts
are unchanged.

## Reconsider when

An approved deployment requires ARM64, or measured delivery costs and native runner
availability justify restoring its portability coverage. This record authorizes
neither a new host nor a paid service.
