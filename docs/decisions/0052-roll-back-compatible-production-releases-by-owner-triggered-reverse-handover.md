# ADR-0052: Roll back compatible production releases by an owner-triggered reverse handover

- **Status:** Proposed
- **Date:** 2026-11-19
- **Owner:** Ruben Hernandez
- **Scope:** Public production runtime on the single VPS
- **Issue:** [#488](https://github.com/rubhern/videogame-platform/issues/488)
- **Related:** [ADR-0036](0036-use-protected-actions-for-production-recovery.md),
  [ADR-0043](0043-reduce-planned-downtime-with-online-captures-and-an-application-handover.md),
  [#455](https://github.com/rubhern/videogame-platform/issues/455),
  [#467](https://github.com/rubhern/videogame-platform/issues/467),
  [#485](https://github.com/rubhern/videogame-platform/issues/485)

## Context

The in-place upgrader (#455) is forward-only. A failed handover (ADR-0043) restores the previous
application by itself, but once a release completes there is no way back to the release before it.
A regression found after a release was reported successful can then only be fixed forward. The
failure tables already allowed an "assessed rollback" while the older image is schema-compatible.
Nothing performed that assessment, and nothing named the release to return to.

Every trusted-main build republishes the Keycloak, edge and localizer images, and the builds are not
reproducible. Two consecutive releases therefore always differ in those digests, even when their
sources did not change. On 2026-11-19, `54fa093` and `9242103` differed in 6 of 10 Keycloak layers,
2 of 7 edge layers and 4 of 8 localizer layers, while the range touched none of their sources. A rule
that requires identical images other than the application never applies to a real release.

A rollback is not a database restore. Flyway is never reverted. Live volumes are never restored
over, and Keycloak users are never recreated. Writes made after the release must survive: ratings,
accounts, identity changes and erasures. An unchanged Flyway version alone does not prove that the
previous code can run against the current state.

## Decision

**Owner-triggered reverse handover, only to the recorded predecessor, only for compatible releases.**

1. **Last-known-good is lineage, not a tag.** It is the `previous` release in
   `upgrades/ACTIVE.json`: a completed upgrade journal whose retained bundle holds the exact
   configuration (its SHA-256 equals the journal's and the pointer's), the reviewed plan (bound by
   its hash) and the pinned image digests, with the application image labelled with that source SHA
   and still present locally.
2. **A read-only assessment decides.** `scripts/production_rollback.py` returns `ALLOW`, `REFUSE` or
   `FORWARD_FIX_OR_RECOVER` with the reasons and the operator action. `ALLOW` requires all of the
   following:
   - the two configurations differ only within the ADR-0043 handover scope, apart from
     **dependency images rebuilt from unchanged sources**. A Keycloak, edge or localizer image
     counts as a rebuild when its Dockerfile, every `COPY` source and the build script are unchanged
     in the range; a test keeps that source table in step with the Dockerfiles;
   - the live Flyway history equals the last-known-good inventory, version and checksum;
   - the realm import and live realm policy are unchanged, and the current release planned no
     realm change;
   - secrets, services and settings are identical;
   - the Git range touches no persisted or identity contract path.

   Incompatibility gives `FORWARD_FIX_OR_RECOVER`, which no retry clears. Missing, corrupt,
   ambiguous or busy evidence gives `REFUSE`. The static evidence is hashed; the owner authorizes
   that hash.
3. **The target keeps the rebuilt dependencies.** It is the last-known-good configuration with the
   active definition of each rebuilt dependency, written and hashed in the rollback journal; for a
   public edge it has a matching private twin. Those services keep running, so the rollback never
   replaces a component with an older build of the same source, and never restarts Keycloak or the
   edge. `ACTIVE.json` names this derived configuration.
4. **Execution reuses the handover in reverse.** It runs under the backup-cycle lock (shared with
   the timers and the #467 deployment) and the recovery lease. A candidate of the last-known-good
   application serves while the application is replaced, then the release is smoked and named in
   `ACTIVE.json`. Any failure restores the release that was running. The reverted upgrade's journal
   becomes `reverted`, so its SHA is never claimed again. A new release needs a new commit. The
   backup units are then repointed at the last-known-good bundle.
5. **Automation level: manual and owner-triggered.** Health checks during a deployment and the
   handover's own failure path stay automatic. Release acceptance and incident detection stay with
   the owner.

| Option | Assessment |
|---|---|
| Manual owner-triggered rollback on the host (chosen) | Smallest change. Reuses the upgrader's locks, journals and handover; works when GitHub or Actions is unavailable, because the claimed operator is verified offline |
| One action through the #467 Actions workflow | Needs a second approved workflow input, host entrypoint and evidence path, for a rare incident action. Revisit if rollbacks become routine |
| Automatic reversal of a failed compatible candidate before acceptance | Already exists: a failed handover restores the previous application. Extending it beyond the deployment would act on symptoms the tool cannot classify |
| Automatic rollback after a functional error or an incompatible change | Rejected: it could undo data-compatible fixes, repeat on flapping signals and cannot be correct across schema or identity changes |

## Consequences

- A compatible regression can be reverted in minutes, without a fence or a backup window. The
  outage profile is the handover's: signed-in BFF sessions end and a write in flight may fail once.
- Any release with a migration, realm change, new secret, setting or service still has no rollback;
  its recovery stays a forward fix or the separately authorized paired restore. Expand/contract
  migrations keep more releases eligible.
- Rolling back reverts every code change in the range, security fixes included. The assessment lists
  this impact, and the owner decides.
- A release whose `ACTIVE.json` names an ingress-relocated configuration, or a predecessor that is
  the first installation, cannot be rolled back. That configuration is not a retained bundle. A
  release that is itself the result of a rollback cannot be rolled back again either; a later
  upgrade from it records the derived configuration as its predecessor, which a rollback accepts.
- The same non-reproducible publications mean the ADR-0043 handover never qualifies for a real
  release either: every release takes the fenced activation. That is outside this decision.
- The Actions deployment is unchanged. A rollback in progress blocks it, the backup cycle, account
  erasure and the upgrader through the shared journal and lock, and a deployment prepared before a
  rollback refuses at apply because its plan no longer matches.
