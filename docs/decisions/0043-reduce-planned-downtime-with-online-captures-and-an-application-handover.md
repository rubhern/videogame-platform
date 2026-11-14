# ADR-0043: Reduce planned downtime with online captures and an application handover

- **Status:** Proposed
- **Date:** 2026-11-14
- **Owner:** Ruben Hernandez
- **Scope:** Scheduled backups, the per-deployment restore proof, application-only releases and the
  edge's behaviour while writers are fenced, on the single production VPS
- **Issue:** [#485](https://github.com/rubhern/videogame-platform/issues/485)
- **Related:** [ADR-0027](0027-revoke-and-end-bff-sessions-in-the-application.md),
  [ADR-0030](0030-limit-public-edge-requests-with-a-caddy-module.md),
  [ADR-0033](0033-select-lite-3-and-immutable-offhost-storage.md),
  [ADR-0036](0036-use-protected-actions-for-production-recovery.md)

## Context

Every planned operation made the product unavailable. The scheduled capture stopped Keycloak, the
edge and the application twice a day: about 2.5 minutes without a maintenance page, recorded as an
accepted limitation of the public release. A deployment fenced the writers for its capture, the S3
publication, the isolated restore on a GitHub-hosted runner, the migrations and the restart. In
deployment run 43241541885 those steps spanned about eleven minutes, the restore alone about six;
the moment the fence closed was not observed, so this is an upper bound, not a measurement.

The causes, read in the tooling:

- The fence stopped the edge too, although the edge holds no database session and already answers
  every request with a maintenance response while the gate is closed.
- A consistent pair of the application and Keycloak databases required stopping their writers:
  two `pg_dump` runs are two snapshots, and an exported snapshot can be imported only in the same
  database.
- The restore proof ran while the writers stayed fenced, so the pair it proved was exactly the
  pre-upgrade point.
- The edge had one upstream, so replacing the only application meant an interruption.

The owner asked for the least possible interruption, without a service outage where feasible
(#485), and approved the options below on 2026-11-14. Multi-host availability, replication, an
orchestrator, another database or a paid service are out of scope.

## Options considered

| Option | Interruption | Why chosen or not |
|---|---|---|
| Keep the edge running behind the closed gate | Same length, but a maintenance response instead of a refused connection | Chosen: the edge holds no session, and the gate already drives its response |
| `pg_dump` of each database online | None | Rejected: two snapshots at different moments; a registration or erasure between them breaks the principal/identity relation. Not one common point |
| Read-only serving behind a short write fence | Writes only | Rejected: Keycloak persists user sessions, so a login or token refresh writes; a database-level read-only mode breaks identity |
| Online physical copy of the whole cluster, dumped from an offline clone | None | Chosen for scheduled captures and the restore proof: one WAL position for both databases, same encrypted pair format |
| Second application instance behind the edge for compatible releases | None for public reads | Chosen, bounded to releases that change only the application image |
| Shared BFF session store so signed-in visitors survive a handover | None for sessions | Deferred: it is ADR-0027's revisit trigger and moves its revocation registry; a separate decision |
| Prove the restore on an earlier pair, fence only for the final pair | Saves the restore from the fenced window | Chosen: the proof covers keys, storage, format and restore path; owner accepted the narrower guarantee |

## Decision

- **The fence leaves the edge running.** The recovery actor stops the application, the migration
  actor, Keycloak and Grafana, never the edge. A fenced capture, restore or upgrade is a 503
  maintenance response on both hosts until the gate reopens.
- **Captures are online by default.** `recovery snapshot` copies the cluster with `pg_basebackup`
  through the PostgreSQL container's local socket, so no new role, password or listener exists.
  The copy goes into a clone with no network and no TCP listener, holding its data in tmpfs,
  labelled per project and always removed; the reconcile timer removes any a crash left. Both
  databases are dumped from the clone, the existing identity check runs on it, and the same
  encrypted pair is produced with `fence: cluster-snapshot`. Its recovery point is taken before the
  copy starts: every commit before it is in the pair, and possibly some made during the copy.
- **The common point is crash-consistent.** An online pair holds exactly what a power loss at that
  WAL position would leave: committed transactions of both databases, nothing in flight. A
  multi-step flow that spans both databases, such as an account erasure, can be captured half done,
  as it could during a fenced capture; the identity check refuses such a pair, and the deletion
  ledger replay still runs on every restore. A pair is still never two independently timed dumps.
- **The fenced capture remains** for every in-place upgrade's pre-upgrade point, and as the
  scheduled cycle's fallback when the host's available memory cannot hold the clone, the largest
  dump and a tenth of the host's memory; the fallback is reported.
- **Deployments restore an online pair.** The Actions deployment captures its restored pair online,
  so the product serves while it is published and restored. Afterwards:
  - a compatible application-only release hands over without any fence;
  - any other release takes a fresh fenced pair, publishes it, and applies. The in-place upgrader
    accepts the earlier restore as proof only for a pair with the same issuer, recipient, images,
    configuration and schema identities, at most three hours older than the final pair.
- **Compatible application-only releases hand over.** The plan records `activation: handover` only
  when nothing but the application and migration image, bound files and reported version changes:
  no other service, setting, secret, Flyway migration, realm import or realm state, and an edge that
  already lists the candidate. The upgrader starts `application-candidate`, the target application on
  edge address `10.203.1.5` with recurring acquisition off. It then drains and stops the
  application, recreates it on the target image, and stops the candidate. The edge lists the
  application first and the candidate second, and fails over only on a failed dial, for at most
  15 seconds. Any failure restores the previous application, which the unchanged schema serves, and
  records `rolled-back`. The candidate is stopped, never removed by the upgrader.

## Consequences

- Scheduled captures no longer interrupt the product. Their cost is the copy's disk and CPU load and
  its memory for a few minutes; a host that cannot afford the memory falls back to the fenced
  capture.
- A compatible application-only release no longer interrupts public reads. Signed-in visitors are
  signed out when the application stops, and sessions created on the candidate end when it stops:
  BFF sessions stay in each instance's memory under ADR-0027, and remembered Keycloak sessions let
  them back in without a password. A write sent on a connection a stopping instance closes may fail
  once and is not replayed.
- During the overlap, an administrative session revocation acts on the application only; the
  candidate's sessions end when it stops, at most minutes later. Do not run a revocation or an
  account erasure during a handover. This short overlap does not trigger ADR-0027's shared-store
  revisit; a second long-lived replica would.
- Every other release still interrupts the product, now only for its fenced capture, publication,
  migrations, realm changes and restart, behind a maintenance response instead of a refused
  connection.
- The restore proof of a fenced release covers an earlier pair of the same schema. The final pair is
  produced by the same dump, encryption and upload, but its own restore is not proven before the
  migration. The periodic host-loss reconstruction still restores pairs in full.
- The edge fails over only on a failed dial. While the application is down and no candidate runs,
  a request waits up to 15 seconds before failing, instead of failing at once.
- Zero visible downtime is reached for scheduled backups and, for public reads, for compatible
  application-only releases. It is not reached for incompatible upgrades or for signed-in sessions.
- Host timings are unproven until the owner runs the supervised cycle and the next deployment. The
  disposable rehearsal proves the mechanism against a client reading through the real edge.

## Revisit when

- Signed-in visitors must survive a handover: decide a shared session store, which reopens ADR-0027.
- A release that adds expand-only migrations should hand over too: classify migrations as
  compatible first, with a rollback rule.
- The online copy's memory no longer fits the host, or measured load from the copy affects serving.
- The final pair of a fenced release must be restore-proven before migration again.
