# ADR-0015: Query published release pages with a bounded PostgreSQL read model

- **Status:** Accepted
- **Date:** 2026-08-19
- **Owner:** Ruben Hernandez
- **Scope:** UC-001 release discovery reads
- **Issue:** [#25](https://github.com/rubhern/videogame-platform/issues/25)

## Context

The first UC-001 implementation loaded every release in the current catalogue
publication into Java and then filtered, sorted, counted, and paged it. Its request
memory and database-to-application transfer were `O(publication releases)` even when
the response needed 20 rows. It also ended ordering at `gameId`, which is not unique
when one game has multiple platform or region releases.

The publication remains valuable: it is a validated, atomically selected version of
the local catalogue and preserves the last valid state when synchronization fails.
It must not imply that the complete publication is one in-memory aggregate.

## Decision

Use an application-owned `ReleaseBrowseReadPort` and an explicit PostgreSQL/JDBC
adapter for UC-001:

1. Application calculates the recent/upcoming window once from one injected-clock
   instant and supplies query criteria, filters, and bounded pagination.
2. One read-only `REPEATABLE READ` transaction selects the sole current publication,
   taxonomies, `COUNT`, and requested page. PostgreSQL performs temporal/status and
   taxonomy filtering, total ordering, `LIMIT`, and `OFFSET`.
3. `release_snapshot` retains the public partial-date representation and gains stored
   derived `period_start`/`period_end` columns. They are query boundaries, not
   invented public dates.
4. A materialized SQL candidate set uses partial GiST range indexes for known recent
   and upcoming windows; a partial index supports the explicit unknown/TBA upcoming
   branch. Only the page crosses into Java.
5. The total order is effective period, lowercase canonical title, `gameId`, then
   unique `releaseId`. The earlier issue criterion that ended at `gameId` is corrected.
6. Unknown non-released/non-cancelled dates are explicitly included as TBA in
   upcoming results and sort after known dates; they do not claim to overlap the
   calendar window.
7. Game and release snapshot rows, including normalized cover delivery state, belong
   to a publication. Platform and region remain stable global product taxonomies;
   their labels may evolve independently. The response ETag hashes the actual JSON
   representation, so such a label or evaluated freshness change also changes the
   validator even when `catalogueVersion` does not.
8. Keep the normalized schema rather than add a separate projection table. The
   measured SQL is clear, the page uses primary-key lookups for game display fields,
   and no costly multi-owner join requires denormalization.

## Alternatives considered

### Keep the complete Java snapshot

Rejected because transfer, allocations, filtering, sorting, and paging grow with the
complete publication and cannot be repaired by a larger heap.

### Add a denormalized release-discovery table

Deferred because the normalized publication tables and evidence-backed indexes meet
the current access path. Reconsider only if measured joins or publication throughput
cannot meet an explicit objective.

### Replace page/offset pagination with cursors now

Deferred to preserve the approved contract. Reconsider when measured deep offsets or
exact counts violate latency/resource objectives, or clients demonstrate a need to
navigate beyond the bounded discovery window efficiently.

### Add Redis, Elasticsearch, or another service

Rejected: there is no measured limitation requiring additional infrastructure.
PostgreSQL, HTTP caching, a stateless application, and horizontal application scaling
remain the first scale path.

## Consequences

### Positive

- Java materializes `O(pageSize)` releases rather than the whole publication.
- Database integrity protects the single current publication, release-date states,
  join cardinality, and published cover delivery state.
- Query semantics are explicit, deterministic, and independently measurable.
- The application remains stateless and provider-independent.

### Negative

- Exact `COUNT(*)` and `OFFSET` still have costs proportional to matching rows and
  page depth inside PostgreSQL.
- Each immutable publication currently duplicates its game/release snapshot rows.
- Global taxonomy label changes do not create a new catalogue version, although they
  do change the representation validator.
- GiST (`btree_gist`) adds index storage and migration work.

## Evidence and triggers

The opt-in `scripts/analyze-release-browse.sh` creates 10k, 100k, or 1M releases and
runs the production queries plus `EXPLAIN (ANALYZE, BUFFERS)`. The accepted 100k run
returned 20 Java items for both views: recent matched 1,183 rows and used its GiST
index in about 1.4 ms for count and 7.3 ms for the page; upcoming matched 2,179 rows
and used a bitmap union of its GiST and TBA indexes in about 6.9 ms for count and
16.3 ms for the page. Both page plans used top-N sorting and indexed game lookups on
the recorded local Docker environment. These are evidence, not portable latency
gates.

Revisit:

- keyset pagination or approximate/deferred counts after measured high-offset or
  count latency violates an agreed objective;
- publication retention/garbage collection first, then partitioning or copy-on-write
  versioned rows, when measured snapshot storage or publication duration is material;
- CDN/reverse proxy when public traffic and cache-hit evidence justify it;
- a read replica only when measured primary read load justifies the consistency and
  operational cost.
