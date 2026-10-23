# Historical synchronization pilot

**Status: three completed private-dev pilots reported by the owner for
[#246](https://github.com/rubhern/videogame-platform/issues/246).**
The measurements below are owner-supplied execution evidence, not synthetic fixtures.
The underlying per-pilot receipts were not attached to this implementation request, so
this record does not claim an independent audit of their CPU, storage or timing traces.
The [operational procedure](../../deploy/private-dev/README.md#historical-synchronization-pilot)
owns commands; this page owns the historical measurements and their limitations.

## Completed measurements and conclusion

| Inclusive month | Games | Duration | Derived throughput |
|---|---:|---:|---:|
| March 2017 | 1,174 | 1,776 s | approximately 2,380 Games/hour |
| November 2020 | 1,921 | 2,702 s | approximately 2,559 Games/hour |
| January 2025 | 2,170 | 2,911 s | approximately 2,684 Games/hour |

The owner reports stable memory of approximately 516–526 MiB across these pilots and
no IGDB retries. These observations support deterministic calendar months, sequential
processing and checkpoints at month boundaries on this private host. They supply no
reason for parallel month processing or distributed infrastructure. A lost partial
month is re-run through UC-009's existing idempotent reconciliation; earlier checkpoints
are retained. PR #368's closed assessment distinguishes known data-quality failures
from operational failures that require an operator before progression resumes.

The intended first explicit historical target is 2017-03-01 through 2026-09-30, including
all supported Games and platforms, with no platform filter. This is an operator-selected
target, not startup behaviour or authorization to import the complete range for acceptance.
No completion ETA is inferred from three months. A small representative interruption/resume
exercise of the orchestrator remains pending owner review and authorized private-dev
promotion; completed pilots alone do not prove resumable orchestration.

## Question

The initial historical backfill is meant to cover every supported IGDB Game and platform from
March 2017 onwards, through unchanged UC-009 discovery and reconciliation
([ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)). Before
#246 decides how to split that target and resume it, it needs measured answers to four
questions about a single month:

- How long does a month take?
- What bounds that time: the provider, PostgreSQL or localization?
- What does a month cost in memory, CPU and storage?
- How do failures and stops behave?

## Method

- One calendar month per run, launched and stopped only by the owner. The completed
  months are 2017-03, 2020-11 and 2025-01: early, middle and recent provider history. Each is a separate, explicit start, and none launches another.
- The trigger is the existing management command, so the run is the ordinary UC-009 run with
  its usual report, coverage, progress log and meters. The private-dev
  [operating procedure](../../deploy/private-dev/README.md#historical-synchronization-pilot)
  owns the commands, and `deploy/private-dev/bin/historical-sync-pilot` owns the mechanics.
- A detached monitor samples every 30 seconds by default. It takes a baseline and a final
  snapshot and writes self-describing evidence. That evidence is labelled as measured,
  estimated or unavailable, and an unavailable value is never filled in.
- Harness schema 2 corrects two measurement defects found in the first pilot's schema-1
  evidence. Provider request time is converted from the base unit the management port
  declares: the application reports milliseconds, so schema 1's raw total read about a
  thousand times too large. A counter that is lazily absent from an otherwise answering
  scrape now reads zero, so localization is no longer unavailable when nothing was
  localized before the pilot started. A failed probe stays unavailable. A re-analysis
  leaves the schema-1 evidence unchanged. It labels the inferred values as estimated, and
  accepts the millisecond reading only where it agrees with the durable report.
- A month that is already covered measures a re-synchronization: mostly unchanged Games and no
  new identities. It is not comparable with a first import, and the evidence records which one
  it was.
- The [synthetic example](../../scripts/fixtures/historical-sync-pilot/synthetic-example/summary.md)
  shows the evidence format. The tests generated it from a fake runtime, so none of its numbers
  is a measurement.

## What existing instrumentation provides

| Measurement | Source | Class |
|---|---|---|
| Run start, end and duration; outcome and stable code; stop request time | `catalogue.synchronization_run` (database clock) | measured |
| Games created, updated, unchanged, deferred and failed; Releases; release dates inspected | Durable run report | measured |
| Provider requests including retries, retries, summed request latency | Durable run report | measured |
| Progress during the run: phase, page, position within the page, cumulative counters | INFO progress log line, at most once a minute | measured, sampled |
| Failure tally by stage and reason (no Game identities) | Finished log line and failure lines | measured |
| Provider requests by success, retries, request time, mapping failures | Application meters, process-cumulative | measured, as a delta |
| Localization items by outcome (`translated`, `reused`, `skipped`, `failed`, `busy`, `fallback`) | `catalogue.localization.content`, process-cumulative | measured, as a delta |
| JVM heap, process CPU, Hikari active and pending connections | Application meters | measured, sampled |
| CPU and memory of the application, PostgreSQL and localization containers | `docker stats` | measured, sampled |
| Database size, connections, longest open transaction, row and commit counters | PostgreSQL statistics | measured, sampled |
| Catalogue Games, Releases and coverage intervals before and after | `catalogue.observability_inventory` | measured |
| Host load, available memory, free evidence and Docker storage | `/proc`, filesystem | measured, sampled |
| Games per hour, seconds per Game, provider requests per second, provider time share | Arithmetic on the above | estimated |

## Remaining gaps

These are not observable without new instrumentation. Each is unavailable in the evidence; none
is approximated.

- **Total size of a month before it runs.** Progress counts pages and Games done, not the
  provider's total, so the harness cannot give an honest ETA during a run.
- **Time per phase.** The split between provider calls, reconciliation writes and localization
  is not timed. Provider time share is estimated from summed latency only.
- **Translation latency, and localization by kind together with outcome.** The meter counts
  outcomes across all kinds.
- **Time spent waiting on the rate limiter, and the per-code breakdown of unsuccessful
  provider requests.** The meter's outcome tags hold that breakdown, but the harness keeps only
  success against the rest.
- **True peaks.** A sampled maximum is a lower bound. Short spikes between samples are missed.
- **Contention attribution.** Meter deltas, database growth and host load include anything else
  the host did meanwhile, such as reads, scheduled runs, backups or other containers.

## Using the evidence for #246

Compare the three measured months with each other, not with a throughput target.

- **Chunk size.** If a month's duration stays well within an operator's session tolerance, and
  well beyond the abandonment period without long heartbeat gaps, month-sized chunks are
  viable. Treat large variation between months, or a month of many hours, as evidence for
  splitting busy months rather than shrinking every chunk.
- **Checkpoint strategy.** Duration and the cost of a lost month set what a restart throws away.
  Stop latency (the stop request against the run's end) and heartbeat gaps show how quickly a
  run reaches a safe point.
- **Concurrency.** Provider requests per second close to the configured ceiling, with a high
  provider time share, mean the run is provider-bound, and parallel chunks would share the same
  rate limit. A low provider share with rising database time, pool waits or long transactions
  points at PostgreSQL instead. Without such evidence, #246 keeps processing chunks sequentially.
- **Resource limits.** Heap and container memory should stay flat across months of very
  different size, because work is designed to be bounded by the page. Growth with month size
  would be a defect to fix before any backfill.
- **Storage and localization.** Database growth per Game gives a projection of storage, as an
  estimate. A high share of `busy`/`fallback` localization outcomes, or helper CPU saturation,
  indicates that localization limits acquisition throughput.
- **Failures.** The failure tally, by stage and reason, shows which retries #246 must bound and
  whether a month should be re-run or accepted as partial.

## Limitations

- One private host, shared with private-dev reads and services; results describe that host
  only.
- Provider behaviour, catalogue state and network conditions at run time are not reproducible.
- A naive projection from three months to the whole period from 2017 onwards is only an
  estimate, and is labelled as one.
