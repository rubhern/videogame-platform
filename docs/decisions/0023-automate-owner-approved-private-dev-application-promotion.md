# ADR-0023: Automate owner-approved private-dev application promotion

- **Status:** Accepted
- **Date:** 2026-10-05
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial, zero-recurring-cost post-MVP delivery
- **Related:** [ADR-0008](0008-use-github-actions-and-ghcr-for-initial-delivery.md), [ADR-0019](0019-host-private-dev-on-an-owner-managed-linux-host.md), [#160](https://github.com/rubhern/videogame-platform/issues/160)
- **Localization lifecycle:** The initial localization-overlay restriction is superseded by [ADR-0024](0024-maintain-catalogue-localization-in-the-private-dev-runtime.md).

## Context

The owner explicitly selected Continuous Delivery with approved application
promotion, preserving the host deployment/recovery mechanism already proven.
The existing forced-command promotion has successful real-host evidence in #160.
The owner explicitly approved simplifying promotion to one dispatch action, without
manual artifact fields or a second environment approval. Accepting this decision
does not claim acceptance of the refined implementation or authorize new host changes;
the runbook owns that evidence boundary.

## Decision

Use a manually dispatched GitHub-hosted Actions job: **workflow_dispatch is the sole
human approval for application promotion**. Run only from main with both actor and
triggering actor equal to rubhern. Keep the dev environment for secrets and an exact
main-branch deployment restriction, without required reviewers, wait timers or custom
approval gates; keep administrator bypass disabled. Connect an ephemeral, narrowly
tagged Tailscale identity to existing OpenSSH and permit only the forced application-promotion command.
Keep privileged deployment code in a root-owned, clean owner-installed checkout;
never let the runner replace host files or obtain Docker/general administration access.
Invoke the existing deployment script, retain protected host logs and return only
sanitized evidence. Secrets remain in GitHub environment/private-host mechanisms.

Pin source to the exact main SHA captured by the dispatch. Derive its unique trusted
main push build, current run attempt and immutable OCI digest from the retained
publication record, with successful quality/security gates and demonstrated main
ancestry. Refuse missing, expired, stale, ambiguous or inconsistent evidence; never
select a latest image or fall back to another revision/run/attempt. Recheck the same
derived tuple before private connectivity and source trust on the host. Block differing
runtime/deployment contracts until their separate reviewed operational rollout and
owner acknowledgement are complete. Keep migration, readiness, smoke, lock and
rollback/forward-fix rules intact. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#artefact-and-delivery)
owns ongoing policy and the [private-dev README](../../deploy/private-dev/README.md#owner-approved-actions-promotion)
owns configuration and operator procedures.

## Alternatives

Automatic deployment after merge conflicts with the owner's selected delivery
boundary. Manual artifact/run fields duplicate evidence already owned by trusted CI
and introduce transcription mistakes. A second environment reviewer action duplicates
the sole owner's dispatch intent. A persistent self-hosted runner or new deployment
service adds trust, maintenance and infrastructure without evidence of need. Public SSH/management
ingress conflicts with the approved private topology.

## Consequences and reconsideration

The owner retains promotion through dispatch and separate runtime approval; approved
application deployment becomes repeatable without another application deployment path. Protected
environment/free-resource eligibility, Tailscale credentials/policy, pinned SSH
identity and GitHub evidence availability become operational dependencies. Public
GitHub metadata can be rate limited, and expired publication evidence blocks
promotion; failure is explicit. The conservative contract check may require a
reviewed tooling-only checkout update without a service upgrade. Initially, active overlays
retained a manual path; ADR-0024 removes the private-dev localization overlay and
permits its existing application container to transition to the standard base runtime.
Other application overlays remain refused. The owner must explicitly remove the old required reviewer
in GitHub Settings before using the refined flow; code refuses the old policy instead
of changing Settings. Multiple build runs for the same SHA are conservatively refused.
The changed host tooling contract needs a reviewed installed checkout update and
acknowledgement; it cannot silently upgrade dependencies. Reconsider only for measured
limitations, changed eligibility, release mode or a demonstrated need for another operational boundary.
