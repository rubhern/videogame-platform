# Release discovery API

- **Status:** Active walking-skeleton implementation
- **Last verified:** 2026-08-22
- **Backend version:** `0.3.2-SNAPSHOT`
- **Issue:** [#25](https://github.com/rubhern/videogame-platform/issues/25)
- **Use case:** [UC-001](../architecture/application/mvp-use-cases.md#uc-001--browse-recent-or-upcoming-releases)
- **Contract:** [`GET /api/v1/releases`](../architecture/api/openapi.yaml)

This guide records the first implemented public product read. The endpoint queries a
bounded page from the current valid PostgreSQL publication, preserves the reviewed
provider-independent HTTP contract, and never calls IGDB or another provider while
serving a request.
Maven generates `ReleasesApi`, `ReleasePage`, and the shared Problem Details models
from that contract. The manual `ReleaseController` implements the generated interface
and is the only layer that maps those transport types to the application use case.

## Behaviour

The required `view` is either `recent` or `upcoming`. Optional `platformId` and
`regionId` values are checked against the complete product-owned local taxonomies.
`page` is one-based and defaults to `1`; `pageSize` defaults to `20` and accepts
`1..100`. Filters and ordering are applied before pagination. A valid filter with no
matches and a page beyond the last both return `200` with `items: []`.

The application derives `evaluatedOn` from its injected clock in `Europe/Madrid`.
The default recent and upcoming windows are rolling six-month windows and are
deployment configuration, not client input. Recent results are known-date releases
with status `released` whose represented period overlaps the recent window. Upcoming
results are non-released, non-cancelled releases whose represented period overlaps
the upcoming window; an explicitly unknown date remains a valid upcoming state and
sorts after known periods.

Ordering is deterministic:

- `recent`: represented period end descending;
- `upcoming`: represented period start ascending, with unknown dates last;
- ties: canonical title ascending, provider-independent `gameId`, and unique
  `releaseId` as the final tie-breaker.

The represented period is used only for window evaluation and ordering. The response
continues to expose the original day, month, quarter, year, or unknown precision and
never invents a more exact date.

## Bounded read architecture

The former request path loaded the complete publication into Java before filtering,
sorting, counting, and paging. UC-001 now uses this dependency direction:

```text
ReleaseController
  -> BrowseReleasesUseCase
  -> ReleaseBrowseReadPort
  -> JdbcReleaseBrowseReadAdapter
  -> PostgreSQL filtered/count/page query
```

Application captures one logical evaluation instant and calculates the business
window and freshness from it. PostgreSQL selects the current publication, filters
status/window/platform/region, counts, applies the complete order, and uses
`LIMIT/OFFSET`. The adapter returns only the requested release items plus count and
small platform/region taxonomies; it never returns a Java snapshot containing every
release. The read transaction is read-only `REPEATABLE READ`, so publication
selection, taxonomy, count, and page observe one database snapshot during concurrent
publication changes.

`period_start` and `period_end` are stored generated columns derived from the approved
day/month/quarter/year representation. They support range queries but do not alter
the public precision. A materialized candidate set makes PostgreSQL apply the partial
GiST range index before page ordering; indexed lookups retrieve game display fields
for matching candidates.

## Snapshot, quality, and covers

`catalogue.catalogue_publication.is_current` identifies the last valid local
publication. The API returns `CATALOGUE_NOT_READY` when no current publication exists;
an empty current publication would instead be a valid empty result. A PostgreSQL read
failure becomes `CATALOGUE_READ_FAILED`. Neither outcome attempts a provider fallback.

Each result retains release status, normalized provenance, provider-update time,
last synchronization and verification times, verification level, review status, and
derived freshness. Freshness is `stale` only after the configured threshold has
elapsed since that release's `last_synchronized_at`. Staleness does not erase a
verified fact or make the snapshot unreadable.

Game and release snapshot rows belong to the publication. Their normalized cover
reference and source-page URL are copied into `game_snapshot`, so a later live
external-reference change cannot silently alter an existing publication. Platform
and region are intentionally stable global product taxonomies rather than
publication copies; their labels may be corrected without republishing the catalogue.

An approved IGDB `provider_cdn_reference` is rendered only from trusted normalized
published state. Application chooses provider cover versus product fallback; the
delivery mapper constructs the allowlisted IGDB image-CDN representation and
attribution. Missing or unusable state resolves to
`/assets/covers/fallback.svg`. Provider image binaries and credentials never enter
the API or product storage.

## HTTP and errors

Successful responses include `X-Correlation-ID`, explicit public `Cache-Control`, and
an opaque strong `ETag` derived from SHA-256 of the serialized JSON representation.
`If-None-Match` uses the standard weak comparison for entity-tag lists, so an
equivalent strong or weak validator returns `304` without a body. Error responses use
`application/problem+json`, `Cache-Control: no-store`, the same correlation ID, and
the stable reviewed codes:

- `FILTER_INVALID`, `PLATFORM_NOT_SUPPORTED`, and `REGION_NOT_SUPPORTED`;
- `PAGINATION_INVALID` and `REQUEST_PARAMETER_UNKNOWN`;
- `CATALOGUE_NOT_READY` and `CATALOGUE_READ_FAILED`;
- applicable delivery codes such as `REPRESENTATION_NOT_ACCEPTABLE`,
  `METHOD_NOT_ALLOWED`, and `INTERNAL_ERROR`.

The ETag deliberately does not equal the catalogue version: evaluated freshness and
global taxonomy labels can change the body while the publication stays constant.
Hashing the wire model preserves HTTP correctness and lets a future reverse proxy or
CDN reuse the same validators without being introduced prematurely.

## Runtime configuration

All properties are read at startup and require a restart to change. They contain no
secrets.

| Environment variable | Default | Purpose |
|---|---:|---|
| `CATALOGUE_RELEASES_RECENT_WINDOW_MONTHS` | `6` | Months before `evaluatedOn` included in `recent` |
| `CATALOGUE_RELEASES_UPCOMING_WINDOW_MONTHS` | `6` | Months after `evaluatedOn` included in `upcoming` |
| `CATALOGUE_RELEASES_FRESHNESS_THRESHOLD` | `P7D` | ISO-8601 duration after which a release is stale |
| `CATALOGUE_RELEASES_CACHE_CONTROL` | `public, max-age=60, stale-while-revalidate=300` | Public response cache policy |

These defaults are the initial operational policy for the private learning MVP. The
approved API conventions deliberately leave exact window lengths, freshness
thresholds, and cache durations configurable; issue #33 may refine them with bounded
synchronization evidence without changing the public schemas.

## Telemetry

Existing `http.server.requests` metrics provide route-template request and latency
evidence. The endpoint also publishes:

| Meter | Bounded tags | Meaning |
|---|---|---|
| `catalogue.releases.requests` | `view`, `outcome` | Successful and failed query count |
| `catalogue.releases.latency` | `view`, `outcome` | Use-case latency |
| `catalogue.releases.result.count` | `view` | Items returned by `200` responses; `304` records no result sample |
| `catalogue.releases.failures` | stable `code` | Failure count by reviewed outcome |

Platform IDs, region IDs, game IDs, query values, correlation IDs, provider values,
and concrete URLs are never metric labels.

## Local exercise and automated evidence

Use a fresh disposable local database when opting into the deterministic seed:

```bash
bash scripts/local-dependencies.sh up
set -a
source .env
set +a
APPLICATION_FLYWAY_ENABLED=true \
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed \
./mvnw -pl backend spring-boot:run
```

Import the local environment and the catalogue release collection from
[`backend/postman/`](../../backend/postman/). The collection covers success,
filtering, contract headers, stable validation, and pagination. Automated tests remain
authoritative and cover framework-independent date/window and freshness policy, safe
cover resolution, the bounded JDBC read adapter, PostgreSQL 18 constraints, HTTP
payloads, empty and stale states, `ETag`/`304`, `CATALOGUE_NOT_READY`, and bounded
telemetry.

```bash
./mvnw -pl backend -Dtest=ReleaseDateTest,ReleaseCatalogueServiceTest test
./mvnw -pl backend -Dtest=JdbcReleaseBrowseReadAdapterIntegrationTest test
./mvnw -pl backend -Dtest=ReleaseApiIntegrationTest test
./mvnw clean verify
```

## Scalability evidence and limits

The opt-in scale tool is intentionally outside normal CI:

```bash
scripts/analyze-release-browse.sh 10000
scripts/analyze-release-browse.sh 100000
scripts/analyze-release-browse.sh 1000000
```

It migrates a fresh PostgreSQL 18 database, generates the requested cardinality,
executes the real adapter, prints `EXPLAIN (ANALYZE, BUFFERS)` for count and page, and
asserts that only 20 release objects reach Java and the relevant indexes are used. On
the recorded local 100k run, recent matched 1,183 rows (about 1.4 ms count and 7.3 ms
page); upcoming including TBA matched 2,179 (about 6.9 ms count and 16.3 ms page).
The plans used the recent/upcoming GiST range indexes, the upcoming TBA index, top-N
sorting, and primary-key game lookups without sequentially scanning the complete
release or game snapshot. Times are diagnostic, not portable CI thresholds.

The JVM work is `O(pageSize)` for release items; PostgreSQL work follows index
selectivity, matching-window cardinality, exact count, and requested offset. Current
page/offset semantics remain appropriate for the bounded discovery window. Reassess
cursor/keyset pagination or count strategy when measured deep-page or count latency
violates an agreed objective. Snapshot rows still grow as publications accumulate;
retention/garbage collection is the first follow-up, followed by partitioning or
copy-on-write only from measured storage/publication evidence. CDN and read replicas
remain future traffic/load triggers, not current dependencies.

## Semantic Versioning assessment

Issue #25 adds a compatible public capability to the pre-1.0 backend. The Maven
reactor therefore increments from `0.2.0-SNAPSHOT` to `0.3.0-SNAPSHOT`. The reviewed
OpenAPI contract first incremented from `1.0.0` to `1.0.1` for the compatible
endpoint implementation and now to `1.1.0` for compatible `int64` page totals and
clarified bounded-query/order semantics. The composition-root correction is a
compatible internal fix, so the backend increments from `0.3.0-SNAPSHOT` to
`0.3.1-SNAPSHOT` without changing the OpenAPI contract.
The PostgreSQL JDBC security remediation then increments the backend patch to
`0.3.2-SNAPSHOT`; the release HTTP contract remains unchanged.
