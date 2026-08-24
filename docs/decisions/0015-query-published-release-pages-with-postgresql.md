# ADR-0015: Query published release pages with a bounded PostgreSQL read model

- **Status:** Accepted
- **Date:** 2026-08-19
- **Owner:** Ruben Hernandez
- **Scope:** UC-001 release discovery reads
- **Issue:** [#25](https://github.com/rubhern/videogame-platform/issues/25)

## Context

The first UC-001 implementation loaded a complete catalogue publication into Java,
then filtered, sorted, counted and paged it. Request memory and database transfer were
`O(publication releases)`, and ordering ended at a non-unique `gameId`. Publication
atomicity remains valuable; full in-memory materialization does not.

## Decision

- Use an application-owned `ReleaseBrowseReadPort` with an explicit PostgreSQL/JDBC
  adapter.
- In one read-only `REPEATABLE READ` transaction, select the sole current publication
  and let PostgreSQL filter, count, deterministically order, `LIMIT` and `OFFSET`.
- Materialize only `O(pageSize)` releases in Java.
- Represent partial dates publicly as entered, while stored derived
  `period_start`/`period_end` columns support range queries without inventing dates.
- Use evidence-backed partial GiST indexes for known recent/upcoming ranges and a
  partial index for the explicit unknown/TBA upcoming branch.
- Order by effective period, lowercase canonical title, `gameId`, then unique
  `releaseId`; unknown upcoming dates sort after known dates.
- Keep normalized publication tables. Game/release snapshots belong to a publication;
  platform/region taxonomies remain stable global data. Hash the actual JSON for the
  ETag so label or evaluated-freshness changes invalidate it.

## Alternatives considered

- **Complete Java snapshot:** rejected because it cannot bound transfer or memory.
- **Denormalized discovery table:** deferred while measured normalized queries meet
  the access path.
- **Cursor pagination:** deferred to preserve the approved contract until deep-offset
  or exact-count evidence requires change.
- **Redis/search service:** rejected without a measured PostgreSQL limitation.

## Consequences

Query behavior is bounded, deterministic, measurable and stateless. Exact counts and
offsets still cost work in PostgreSQL, immutable publications duplicate snapshot rows
and GiST adds migration/index storage.

## Evidence and reconsideration triggers

`scripts/analyze-release-browse.sh` provides opt-in representative data and production
query plans. The accepted 100k-release local run returned 20 Java items: recent
matched 1,183 rows (about 1.4 ms count, 7.3 ms page) and upcoming matched 2,179 rows
(about 6.9 ms count, 16.3 ms page), using the intended indexes. These observations are
historical evidence, not portable latency gates.

Revisit keyset pagination/count strategy for measured high-offset or count problems;
retention before partitioning/copy-on-write for material snapshot cost; intermediary
caching for demonstrated public traffic; and replicas or another read store only for
measured primary-load or query limitations.
