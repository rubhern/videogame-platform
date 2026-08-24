# Learning MVP application use cases

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Application operations and guarantees; not HTTP, screens, SQL, or framework classes

## Catalogue

| ID | Operation | Actor | Required behaviour |
|---|---|---|---|
| `UC-001` | Browse recent/upcoming releases | Visitor | Application derives evaluation date/window; PostgreSQL filters, counts, uniquely orders, and pages local publication; TBA upcoming sorts last; stale/empty/fallback are valid states |
| `UC-002` | Search bounded catalogue | Visitor | Search canonical titles/approved aliases only; zero/multiple matches are valid; never call provider |
| `UC-003` | View game details | Visitor/optional user | Return coherent game/releases/eligibility/aggregate; personal rating is a separate authenticated resource; unavailable aggregate/fallback may degrade a valid page |
| `UC-009` | Synchronize bounded catalogue | Scheduler/operator | Fetch curated references, normalize/validate, stage new games/changed covers, publish coherent updates, preserve last valid snapshot and cover approval on failure |

`UC-001` ordering ends in unique `releaseId` after effective period, canonical title,
and `gameId`. Request memory is `O(pageSize)` plus bounded taxonomy; persistent
filtering/counting/pagination never occurs over a complete Java snapshot.

## Identity and ratings

| ID | Operation | Actor | Required behaviour |
|---|---|---|---|
| `UC-004` | Authenticate and resume rating | Visitor | Store short-lived tamper-resistant context; derive user from principal; atomically consume/replay-protect; return to allowlisted game context |
| `UC-005` | Create rating | Authenticated user | Validate 1–10 and current eligibility; prevent duplicate; update personal/aggregate coherently |
| `UC-006` | Update rating | Authenticated owner | Scope by principal + game; validate value/eligibility/concurrency; preserve previous state on failure |
| `UC-007` | Delete rating | Authenticated owner | Scope by principal + game; delete regardless of current eligibility; update aggregate coherently |
| `UC-008` | View/search/sort `Mis puntuaciones` | Authenticated user | Scope by user before search/sort/count/page; default updated-descending; unique `gameId` tie-breaker |

The client never supplies a trusted user/evaluation date. Scoped absence returns
`RATING_NOT_FOUND` without revealing another user's state. Authentication cancellation
or invalid/expired/replayed context creates no rating. Any failed rating command
preserves personal and aggregate state.

## Common result and failure rules

- Product identifiers are internal; provider types/IDs do not escape their adapter.
- Date precision, provenance, verification, review, and freshness remain explicit.
- Personal and aggregate ratings remain separate.
- Empty, no-rating, stale-but-usable, fallback-cover, ineligible, and unavailable
  aggregate states are not generic technical errors.
- Stable codes drive clients; localized copy is delivery-owned. Technical responses
  expose correlation, never secrets, raw provider payloads, SQL, or stack traces.

| Category | Principal codes / guarantees |
|---|---|
| Validation | `SEARCH_QUERY_INVALID`, `FILTER_INVALID`, `PLATFORM_NOT_SUPPORTED`, `REGION_NOT_SUPPORTED`, `SORT_INVALID`, `RATING_VALUE_INVALID`; do not execute invalid work |
| Authentication/replay | `AUTHENTICATION_REQUIRED/FAILED/CANCELLED`, `RETURN_CONTEXT_INVALID/EXPIRED/REPLAYED`; no duplicate logical command |
| Domain/conflict | `GAME_NOT_FOUND`, `RATING_NOT_ELIGIBLE`, `RATING_ALREADY_EXISTS`, `RATING_NOT_FOUND`, `RATING_WRITE_CONFLICT`, `RELEASE_DATA_REVIEW_REQUIRED`; preserve valid state |
| Local reads | `CATALOGUE_NOT_READY`, `CATALOGUE_READ_FAILED`, `RATING_STATISTICS_READ_FAILED`, `PERSONAL_RATINGS_READ_FAILED`; never request-path provider fallback or cross-user partial data |
| Writes | `RATING_WRITE_FAILED`, `SYNCHRONIZATION_WRITE_FAILED`; previous valid state remains |
| Provider normalization | `PROVIDER_AUTHENTICATION_FAILED/RATE_LIMITED/UNAVAILABLE/RESPONSE_INVALID/MAPPING_FAILED`, `EXTERNAL_REFERENCE_CONFLICT`, `RELEASE_DATA_INVALID`, `COVER_REFERENCE_INVALID`; isolate candidate and keep last valid local data |

Database constraints/transactions must enforce concurrent rating uniqueness and
coherent writes. Synchronization never expands catalogue membership or approves a
cover implicitly. Exact HTTP mapping is owned by OpenAPI and API conventions.
