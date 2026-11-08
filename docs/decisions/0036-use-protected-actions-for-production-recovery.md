# ADR-0036: Use protected Actions for private-production recovery

- **Status:** Accepted
- **Date:** 2026-11-08
- **Owner:** Ruben Hernandez
- **Scope:** Owner-approved private-production delivery and recovery-key custody for #467; repository implementation, initial configuration and real-host acceptance remain separate
- **Related:** [ADR-0008](0008-use-github-actions-and-ghcr-for-initial-delivery.md), [ADR-0033](0033-select-lite-3-and-immutable-offhost-storage.md), [#467](https://github.com/rubhern/videogame-platform/issues/467)

## Context

Private production, automatic paired backups, immutable off-host S3 storage and the
forward-only #455 updater already exist. Each upgrade requires a real isolated restore
of its fresh backup while the writer fence remains continuously closed. The only confirmed
private recovery key was on the owner's WSL2 workstation, which successfully restored
PostgreSQL and Keycloak in #455. The owner requires automated delivery with that workstation
offline and explicitly rejects an additional recovery server.

## Decision

The owner explicitly authorizes a copy of the recovery decryption material in the protected
GitHub environment `production-recovery`, restricted to `main` and explicit owner approval.
Prefer an export containing only the matching encryption/decryption subkey with an unusable
primary-key stub. A primary-encryption key is supported only when the current backup key
cannot be separated into a subkey; that explicit configuration preserves the broader custody
risk instead of silently importing a master key. No other usable signing/authentication secret
or unrelated key is admitted.

Use separate ephemeral GitHub-hosted recovery and deployment jobs. Recovery receives only
its key/passphrase and existing read-only S3 identity. Deployment receives only restricted
Tailscale/SSH access; the production host never receives decryption material. Both jobs require
protected-environment approvals before capture. Reuse #455's canonical backup, exact-version
S3 retrieval, isolated PostgreSQL restore and continuous fence checks. The host independently
requires the same run's recovery job to finish successfully after cleanup before applying.

Keep the original WSL key as an independent recovery copy. Import the Actions copy only
in temporary owner-only volatile storage; remove it and all restored resources on completion.
No secret, plaintext dump, restored database, cache or key is an Actions artifact. Retained
cross-job evidence contains only bounded non-secret metadata and attestations. No new server,
cloud storage, public ingress or provider resource is introduced.

This scoped decision amends the earlier production policy separating every recovery key from
CI. Host/runtime secrets and signing/reconstruction material retain their existing boundaries.
The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns current policy; the [operator guide](../../deploy/public-production/README.md#owner-approved-actions-deployment-467)
owns setup, export, dispatch, cleanup and incident procedures.

## Consequences and reconsideration

GitHub's protected environment and approved workflow code become trusted with the ability
to decrypt retained backups. A compromised approved runner/workflow can read plaintext;
masking and cleanup do not eliminate that trust. Separating jobs prevents ordinary deployment
credentials from accessing recovery material, but does not make a malicious approved workflow
safe. A subkey still decrypts historical backups encrypted to that subkey. Maintain independent
WSL recovery for GitHub/account loss and review/revoke the Actions copy during an incident.

Runner memory, volatile storage and disk must cover the actual database sizes before fencing.
Outgrowing standard hosted-runner capacity refuses deployment and triggers a new bounded
resource decision; it never authorizes a larger paid runner or a weaker restoration check.
Reconsider custody on a GitHub/runner compromise, unavailable owner approval, changed key
capabilities, material data sensitivity or sustained capacity/cost limits. Public production
and live VPS changes remain separately authorized; recording this decision copies no key,
creates no secret, and executes no deployment.
