# ADR-0008: Use GitHub Actions and GHCR for initial delivery

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP in a public repository

## Context

The repository needs repeatable validation and immutable deployment artifacts without
paid runners or building unreviewed source on the target VM.

## Decision

- Use GitHub Actions for pull-request validation, trusted `main` integration and
  deployment orchestration.
- Publish the packaged application as an OCI image to GHCR, identified for deployment
  by immutable digest.
- Build the required `linux/arm64` image and retain `linux/amd64` compatibility where
  the workflow declares it.
- Keep untrusted pull-request jobs read-only and secret-free; only trusted protected
  contexts may publish or deploy.
- Keep the target host a pull-and-run environment; do not compile application source
  there.
- Use only eligible free GitHub resources for the current scope.

Workflow selection and evidence are canonical in the
[delivery lifecycle](../development/delivery-lifecycle.md); deployment mechanics are
canonical in the [platform design](../architecture/deployment/mvp-platform-and-delivery.md).

## Alternatives considered

- **OCI-native CI/registry or Docker Hub:** rejected because they add another
  delivery boundary without current value.
- **Self-hosted runner/build on dev VM:** rejected because it expands trust and makes
  delivery depend on the target environment.

## Consequences

Validation and image provenance remain close to the repository, and deployment uses
one immutable artifact. GitHub/GHCR availability and retention remain dependencies,
and workflow permissions require continued review.

## Reconsider when

Revisit for material cost, retention, supply-chain, compliance or availability needs
that the current public-repository/free-resource model cannot meet.
