# ADR-0039: Let private-dev promotion install its own revision

- **Status:** Accepted
- **Date:** 2026-11-11
- **Owner:** Ruben Hernandez
- **Scope:** Owner-dispatched application promotion to private `dev`; public production is unchanged
- **Supersedes:** The installed runtime/deployment-contract gate, owner contract acknowledgement, host GitHub evidence recheck and application-overlay refusal in [ADR-0023](0023-automate-owner-approved-private-dev-application-promotion.md), and the requirement in [ADR-0024](0024-maintain-catalogue-localization-in-the-private-dev-runtime.md) that localizer changes be rolled out and acknowledged before promotion
- **Related:** [ADR-0019](0019-host-private-dev-on-an-owner-managed-linux-host.md), [ADR-0025](0025-host-public-production-on-a-deferred-single-vps.md), [ADR-0037](0037-align-local-and-private-dev-with-production.md)

## Context

Under ADR-0023 the host ran promotion from a root-owned checkout that only the owner could
update. Promotion refused any revision whose deployment files differed from that checkout
and from an owner-acknowledged fingerprint. The host also re-read GitHub evidence itself,
and refused application containers created with an overlay it did not recognise.

Private `dev` is a test environment, and the owner does not want additional protection for
it. Between September and this decision, 82 of main's 191 commits touched the fingerprinted
files. Of 107 promotion runs, 27 failed and none of those failures came from the
application. Fifteen were contract mismatches, five were dispatched before that commit's main CI had finished,
four were exhausted GitHub `502` retries and two were overlay refusals. Each contract
mismatch needed a manual host checkout update and acknowledgement, even when the next
promotion would have recreated the only changed service anyway.

Public production does not fail this way: every deployment materialises the operational
files of the exact revision it deploys, so configuration travels with the release.

## Decision

Make private-dev promotion follow the production pattern.

- The forced-command host entry point fetches `origin/main` and requires the dispatched
  commit to be reachable from it. Under the deployment lock, it moves the root-owned
  checkout to that commit and runs that commit's deployment script. Hand edits in the
  checkout are discarded, and Git stays the source of truth.
- The deployment script reconciles the runtime before migrating. From the cache, it builds
  locally built images and runs Compose `up` on every persistent service. Compose
  recreates only the services whose configuration or image changed. Then it activates
  the application and runs the existing smoke checks.
- Overlays come only from persistent owner flags in the protected `runtime.env`, never from
  how a running container was created.
- The dispatch workflow waits, within a bound, for the commit's main build and security
  runs instead of refusing while they are still running. It retries transient GitHub
  reads for longer. Main ancestry, the published digest and the OCI labels remain
  verified. The host no longer calls the GitHub API.
- Remove the deployment-contract fingerprint and its acknowledgement file.

The workflow dispatch remains the owner's only approval. The restricted SSH identity,
the forced command, the root-owned checkout and the protected runtime secrets are unchanged.

## Alternatives

- **Keep the gate and script the manual rollout.** The rollout would take one command
  instead of several, but every runtime change would still block promotion until someone
  ran it.
- **Classify each contract change and block only "dangerous" ones.** The classification
  would itself drift and keep producing surprise refusals.
- **Automatic deployment on every merge.** This would remove the owner's deliberate
  promotion decision, which ADR-0023 keeps.

## Consequences and reconsideration

A merged change to private-dev Compose, Keycloak, PostgreSQL, observability or localizer
configuration takes effect on the next promotion without a separate host step. Such a
change may restart those services, which is acceptable in a test environment. Anyone who
can merge to main and dispatch can now change root-run deployment code on the host. That
was already the trust boundary for the application image.

Some steps remain manual, and promotion fails until the owner does them:

- a new protected secret or `runtime.env` value that runtime validation requires;
- an operation that container recreation does not perform, such as aligning an existing
  Keycloak realm, changing database roles or the Grafana reader password, or installing
  localizer model weights.

The [private-dev README](../../deploy/private-dev/README.md) owns those procedures.

Reconsider if private `dev` starts holding data or credentials that must not be exposed to
main, or if a reconciliation restart makes promotion unreliable.
