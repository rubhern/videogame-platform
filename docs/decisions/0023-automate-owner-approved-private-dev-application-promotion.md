# ADR-0023: Automate owner-approved private-dev application promotion

- **Status:** Proposed
- **Date:** 2026-10-05
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial, zero-recurring-cost post-MVP delivery
- **Related:** [ADR-0008](0008-use-github-actions-and-ghcr-for-initial-delivery.md), [ADR-0019](0019-host-private-dev-on-an-owner-managed-linux-host.md), [#160](https://github.com/rubhern/videogame-platform/issues/160)

## Context

The owner explicitly selected Continuous Delivery with approved application
promotion, preserving the host deployment/recovery mechanism already proven.
The concrete credential and restricted-host trust boundary below remains proposed
for implementation review; acceptance does not authorize configuring the real host.

## Proposed decision

Use a manually dispatched GitHub-hosted Actions job behind owner approval in the
protected dev environment. Connect an ephemeral, narrowly tagged Tailscale identity
to existing OpenSSH and permit only the forced application-promotion command.
Keep privileged deployment code in a root-owned, clean owner-installed checkout;
never let the runner replace host files or obtain Docker/general administration access.
Invoke the existing deployment script, retain protected host logs and return only
sanitized evidence. Secrets remain in GitHub environment/private-host mechanisms.

Promote explicit immutable digests only with successful trusted-main quality,
security and publication evidence. Recheck source trust on the host. Block differing
runtime/deployment contracts until their separate reviewed operational rollout and
owner acknowledgement are complete. Keep migration, readiness, smoke, lock and
rollback/forward-fix rules intact. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#artefact-and-delivery)
owns ongoing policy and the [private-dev README](../../deploy/private-dev/README.md#owner-approved-actions-promotion)
owns configuration and operator procedures.

## Alternatives

Automatic deployment after merge conflicts with the owner's selected delivery
boundary. A persistent self-hosted runner or new deployment service adds trust,
maintenance and infrastructure without evidence of need. Public SSH/management
ingress conflicts with the approved private topology.

## Consequences and reconsideration

The owner retains promotion and runtime approval; approved application deployment
becomes repeatable without another application deployment path. Protected
environment/free-resource eligibility, Tailscale credentials/policy, pinned SSH
identity and GitHub evidence availability become operational dependencies. Public
GitHub metadata can be rate limited, and expired publication evidence blocks
promotion; failure is explicit. The conservative contract check may require a
reviewed tooling-only checkout update without a service upgrade. Active overlays
retain their manual path. Reconsider only for measured limitations, changed
eligibility, release mode or a demonstrated need for another operational boundary.
