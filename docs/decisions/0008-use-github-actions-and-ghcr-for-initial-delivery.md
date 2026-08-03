# ADR-0008: Use GitHub Actions and GHCR for initial delivery

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP in a public repository
- **Related platform:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

## Context

The source, pull requests, and existing documentation/OpenAPI workflow already use
GitHub. The platform needs reproducible CI, an immutable OCI image, provenance from
commit to deployment, ARM64 support for OCI Ampere A1, and zero recurring cost.

As verified on 2026-08-03, [standard GitHub-hosted Actions runners are free for public
repositories](https://docs.github.com/en/billing/concepts/product-billing/github-actions),
and [GitHub Container Registry image storage and bandwidth are currently
free](https://docs.github.com/en/billing/concepts/product-billing/github-packages).
GitHub states it will provide notice before changing the container-registry policy.

## Decision

Use GitHub Actions for pull-request validation, trusted `main` builds, image
publication, and manually approved initial `dev` deployment orchestration.

Use GHCR for one public OCI application image linked to the public repository. Publish
immutable commit and content-digest references with `linux/arm64` support and,
preferably, `linux/amd64` in the same manifest.

Pull requests receive read-only permissions and no provider/deployment secrets.
Trusted `main` workflows use the minimum `packages: write` permission to publish.
Remote deployment uses a protected environment and least-privilege OCI/Tailscale
credentials. Third-party actions are pinned to reviewed immutable commit SHAs before
the implementation pipeline handles secrets or deployment.

Do not use paid larger runners. Retain only useful build evidence and configure GitHub
budgets to stop chargeable usage if current pricing changes. The container image is
public and MUST contain no secret, private configuration, personal data, raw provider
payload, or copied provider image binary.

## Alternatives considered

### OCI DevOps and OCI Container Registry

This could consolidate the platform, but creates more OCI-specific delivery coupling
and additional free-tier checks while GitHub already owns source and review.

### Docker Hub

It supports public images but adds another account and less direct source-to-workflow
provenance.

### Self-hosted runner on the dev VM

It avoids hosted-runner dependency but allows public repository jobs to reach a
privileged persistent environment and consumes constrained VM resources. It is not
accepted for untrusted pull-request code.

### Build directly on the dev VM

This avoids a registry but breaks build-once promotion, weakens provenance, and places
build tooling and source on the runtime host.

## Consequences

### Positive

- Source, review, checks, image, and deployment evidence remain traceable in one
  established platform.
- Standard public-repository Actions and current GHCR container usage meet the 0 EUR
  constraint.
- OCI images remain portable to another host or registry.

### Negative

- The delivery path depends on GitHub availability and current free policies.
- Public images expose compiled application contents and dependency metadata.
- Multi-architecture builds increase pipeline duration and complexity.

## Risks and mitigations

- **Supply-chain compromise:** least privilege, immutable action pins, protected
  environments, dependency/image scanning, and digest deployments.
- **Secret exposure:** no secrets in pull-request workflows or image layers; rotate on
  suspected exposure.
- **Pricing change:** zero-spend budgets, policy review, retention limits, and a
  documented registry/runner exit path.
- **ARM-only failure:** build/test ARM64 before deployment and retain AMD64 portability.
- **Unbounded images:** apply retention after keeping the deployed and previous known
  healthy digests.

## Follow-up actions

- Extend the current documentation workflow incrementally as application code appears.
- Add trusted multi-architecture build, scan, SBOM/provenance where useful, and GHCR
  publication.
- Configure the `dev` GitHub environment with manual owner approval and least-privilege
  deployment credentials.
- Record and test migration to another OCI registry before GHCR becomes a hard lock-in.
