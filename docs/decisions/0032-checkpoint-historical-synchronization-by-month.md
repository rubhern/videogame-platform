# ADR-0032: Checkpoint historical synchronization by calendar month

- **Status:** Proposed for owner review; implementation prepared under #246
- **Date:** 2026-10-23
- **Owner:** Ruben Hernandez

## Context

The owner requests resumable historical acquisition and its administration UI. Three
[private-dev pilots](../research/historical-sync-pilot.md) support one calendar month at a
time without parallel processing. UC-009 already owns bounded provider paging, stable
identity, reconciliation and fenced per-Game writes; #245 coverage and #368 assessment
remain authoritative. A decades-long request or a browser-held cursor cannot supply
restart-safe orchestration.

## Proposed decision

Keep one explicit current historical target and its month checkpoint in Catalogue's
PostgreSQL schema, independent of pruned ordinary run history. A completed target may be
replaced explicitly; an unfinished one must be resumed. Clip boundary months to requested
dates. Replay an incomplete month through UC-009, preserving completed months, including
legitimately empty months. Continue through known data-quality outcomes only according to
the existing assessment; operational failures pause for explicit resume.

Use a fixed session advisory lock for orchestration admission, with no open transaction
across provider work, and an execution token to fence old checkpoint writers. UC-009 keeps
its existing run fencing and one active run invariant. An in-process sequential executor
owns no correctness state. Two durable slots, fixed to the existing near-term and upcoming
policies, retain due windows when UC-009 is occupied. Repeated due events coalesce per policy;
the existing scheduler thread drains them under a scheduled-turn reservation before another
historical month. Startup performs no historical start or resume.

Expose local status and explicit start/stop/resume through the existing operator BFF and
OpenAPI boundary; poll rather than add a push channel. Ordinary run controls and coverage
remain available. The [solution architecture](../architecture/mvp-solution-architecture.md#historical-acquisition-orchestration)
owns placement and resource bounds; the
[use case](../architecture/application/mvp-use-cases.md#historical-catalogue-synchronization-uc-017-post-mvp-246)
owns guarantees.

## Alternatives and consequences

A provider-Game checkpoint would need durable candidate/completeness semantics inside
UC-009 and is not supported by the pilot evidence. Restarting the whole target wastes
completed months. Parallel months, another datastore, broker or separate worker service
have no measured justification.

The chosen checkpoint can replay up to one month and its counters measure processing
across months, never unique catalogue membership. Two dedicated ownership connections
remain bounded and independent of the transaction pool. A crashed worker is observable
as interrupted; an ordinary run that remains alive still prevents conflicting execution.
Historical operational failures require explicit resume. Pending regular intent survives
restart and is drained when scheduling is enabled; this does not reconstruct cron occurrences
missed during downtime. A scheduled window interrupted ambiguously may replay idempotently.
Terminal scheduled failures wait for the next normal cron. Disabling a periodic policy cancels
its slot, independently of historical stops. Changed windows use fenced completion so a late
result cannot erase newer intent. This is a bounded two-policy deferral, not a generic queue.

## Reconsideration triggers

Revisit only on measured month duration or replay cost that makes month boundaries
insufficient, unacceptable regular-update delay within one month, ownership-session
capacity pressure, or a separately approved concurrency/operational requirement.
