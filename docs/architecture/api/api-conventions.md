# Learning MVP API Conventions

- **Status:** Draft
- **Version:** 0.1
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-30
- **Review state:** Awaiting owner review
- **Scope:** Private, non-commercial learning MVP
- **Architecture:** [Learning MVP solution architecture](../mvp-solution-architecture.md)
- **Domain model:** [Learning MVP domain model](../domain/mvp-domain-model.md)
- **Use cases:** [Learning MVP use cases and relevant errors](../application/mvp-use-cases.md)
- **BFF decision:** [ADR-0003](../../decisions/0003-use-a-same-origin-bff-and-http-json-api.md)
- **Catalogue decision:** [ADR-0004](../../decisions/0004-synchronize-and-serve-local-catalogue-data.md)

> This document is a reviewable proposal. Its conventions become binding only after
> the owner approves it. The OpenAPI contract must not be marked approved while this
> document remains `Draft`.

## 1. Purpose

This document defines the HTTP and JSON conventions for the first browser-facing API
of VideoGame Platform. It translates the approved domain, application, and solution
architecture contracts into consistent delivery rules before endpoint schemas are
written in OpenAPI.

The conventions optimize for:

- one same-origin browser client through a server-side BFF;
- explicit provider-independent contracts;
- correct HTTP semantics;
- secure personal-data handling;
- stable client behaviour and automated contract testing;
- simple operation by one person;
- compatibility with future API Management without requiring it now.

This document does not select a framework, identity-provider product, database
product, hosting platform, or API Manager.

## 2. Normative language

`MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, and `MAY` express the intended strength
of a convention. While this document is `Draft`, every convention remains proposed.
After approval:

- `MUST` and `MUST NOT` are contract requirements;
- `SHOULD` and `SHOULD NOT` require a documented reason to deviate;
- `MAY` identifies an optional capability.

OpenAPI and implementation behaviour must not silently contradict an approved
convention. A necessary conflict must be resolved here or recorded as an ADR.

## 3. API boundary

### 3.1 Included

The initial product API covers:

- recent and upcoming release discovery;
- bounded-catalogue search;
- public game details;
- current-user personal-rating retrieval;
- current-user rating creation, update, and deletion;
- `Mis puntuaciones`;
- minimal BFF session-state discovery;
- stable errors and explicit degraded states.

### 3.2 Excluded

The initial product API does not expose:

- IGDB credentials, raw responses, IDs, or provider taxonomy;
- catalogue import candidates or curation operations;
- catalogue synchronization as a browser operation;
- user administration or identity-provider management;
- other users' personal ratings;
- a provider-image proxy or copied provider binaries;
- capabilities outside the approved MVP;
- a public external-consumer API or developer portal.

`UC-009` remains an internal scheduler or operator workflow. A future operational
contract does not belong in the first browser-facing OpenAPI document.

## 4. Protocol and media types

- Non-local traffic uses HTTPS.
- Browser API traffic uses the same origin as the frontend.
- The product API uses HTTP and JSON.
- Successful JSON responses use `application/json`.
- Error bodies use `application/problem+json`.
- JSON request bodies use `application/json`.
- JSON uses UTF-8.
- The API does not use JSONP, XML, generic action endpoints, gRPC, or API Management.

Clients send:

```http
Accept: application/json, application/problem+json
```

Commands with a body also send:

```http
Content-Type: application/json
```

Unsupported request media types return `415 Unsupported Media Type`. A client that
rejects every supported response representation receives `406 Not Acceptable`.

## 5. URI and naming conventions

### 5.1 Base path and version

The first contract uses:

```text
/api/v1
```

The major version is visible in the path. No unversioned alias is provided.

BFF authorization navigation endpoints use a separate same-origin `/auth` namespace
because redirects and callbacks are not product resources.

### 5.2 Resource and field naming

- Paths use lowercase plural nouns.
- Multi-word path segments use kebab case.
- Paths do not contain verbs such as `/getGames` or `/createRating`.
- JSON properties and query parameters use `lowerCamelCase`.
- Enum wire values use `lower_snake_case`.
- Stable application codes remain `UPPER_SNAKE_CASE`.
- Acronyms follow normal camel case: `gameId`, not `gameID`.
- A trailing slash is not canonical.
- Persistence, provider, class, and framework names are not public concepts.

### 5.3 Identity

- `gameId`, `platformId`, and `regionId` are opaque product-owned strings.
- Clients compare identifiers for equality only.
- Clients never infer provider identity from an identifier.
- A `slug` may support navigation but never replaces `gameId`.
- `UserId`, issuer, subject, email, and identity-provider tokens are never accepted
  from the browser as rating-owner identifiers.
- A personal rating is addressed by authenticated principal plus `gameId`.
- No public `ratingId` is introduced because rating identity is
  `authenticated UserId + GameId`.
- Named reusable string schemas represent identifiers; UUID format is not assumed.

## 6. Proposed resource map

| Method | Path | Authentication | Use case | Purpose |
|---|---|---|---|---|
| `GET` | `/api/v1/releases` | Public | `UC-001` | Browse recent or upcoming releases. |
| `GET` | `/api/v1/games` | Public | `UC-002` | Search the bounded catalogue. |
| `GET` | `/api/v1/games/{gameId}` | Public | `UC-003` | Read public game, release, eligibility, and aggregate context. |
| `GET` | `/api/v1/session` | Optional | BFF boundary | Read minimal session state and obtain CSRF material when authenticated. |
| `GET` | `/api/v1/me/ratings` | Required | `UC-008` | Read, search, sort, and page `Mis puntuaciones`. |
| `GET` | `/api/v1/me/ratings/{gameId}` | Required | `UC-003`, `UC-008` | Read the current user's rating for one game. |
| `PUT` | `/api/v1/me/ratings/{gameId}` | Required | `UC-005`, `UC-006` | Create or update according to an explicit precondition. |
| `DELETE` | `/api/v1/me/ratings/{gameId}` | Required | `UC-007` | Delete the current user's active rating. |

Public game details and personal rating state are separate resources. This prevents
the public representation from varying by session and prevents shared caches from
storing personal data.

OpenAPI may refine schemas and descriptions but must not add product capabilities
without updating the approved application scope.

## 7. HTTP semantics

### 7.1 Queries

`GET` is safe and does not change product state. Query handlers:

- read local product state only;
- never call IGDB in the request path;
- return `200 OK` for valid representations, including empty collections;
- may return `304 Not Modified` for documented conditional public reads.

`HEAD` may be supported when its headers match `GET`. It is not required initially.

### 7.2 Create and update rating

One URI represents the active personal rating for a game:

```text
/api/v1/me/ratings/{gameId}
```

`PUT` carries the complete writable state:

```json
{
  "value": 9
}
```

The client declares exactly one intent:

| Intent | Required header | Success | Failed precondition |
|---|---|---|---|
| Create | `If-None-Match: *` | `201 Created` | `412 Precondition Failed` with `RATING_ALREADY_EXISTS` |
| Update | `If-Match: "<strong-etag>"` | `200 OK` | `412 Precondition Failed` with `RATING_WRITE_CONFLICT` |

If neither precondition is present, or both are present, return
`428 Precondition Required` with `PRECONDITION_REQUIRED`.

Create and update re-evaluate eligibility using the application clock. The client
does not supply an evaluation date.

### 7.3 Delete rating

`DELETE /api/v1/me/ratings/{gameId}` requires the current strong `If-Match`.

- Success returns `200 OK` with no active personal rating and the updated aggregate.
- Absence in the current-user scope returns `404` with `RATING_NOT_FOUND`.
- A stale tag returns `412` with `RATING_WRITE_CONFLICT`.
- A missing precondition returns `428`.
- Current eligibility does not block owner deletion.

The response is `200`, not `204`, because the use case requires updated state.

### 7.4 Authentication navigation

- The BFF uses Authorization Code with PKCE and OpenID Connect when identity claims
  are needed.
- Authentication starts through an allowlisted `/auth` route.
- The callback is owned by the BFF and is not a product command endpoint.
- Logout uses `POST` with CSRF protection.
- Redirects never accept an arbitrary destination.
- Callback retry, reload, or replay executes one logical rating confirmation at
  most once.

Identity-provider URLs and protocol configuration are not product API fields.

## 8. Successful responses

### 8.1 General shapes

A single-resource response returns the representation directly; it has no generic
`data` envelope.

Collections use:

```json
{
  "items": [],
  "page": {
    "number": 1,
    "size": 20,
    "totalItems": 0,
    "totalPages": 0
  }
}
```

Rating commands return the coherent personal and aggregate result:

```json
{
  "personalRating": {
    "gameId": "game_opaque",
    "value": 9,
    "createdAt": "2026-07-30T10:15:30Z",
    "updatedAt": "2026-07-30T10:15:30Z",
    "entityTag": "\"rating-version-opaque\""
  },
  "ratingStatistics": {
    "status": "available",
    "mean": 8.4,
    "count": 17,
    "distribution": {
      "1": 0,
      "2": 0,
      "3": 0,
      "4": 1,
      "5": 1,
      "6": 2,
      "7": 3,
      "8": 3,
      "9": 5,
      "10": 2
    }
  }
}
```

### 8.2 Presence, null, and unknown fields

- Required fields are always present.
- Optional fields are omitted when they do not apply.
- `null` is used only when absence is part of domain meaning.
- Empty string, empty array, zero, omitted, and `null` remain distinct.
- Request command schemas reject unknown properties.
- Clients tolerate additive unknown response fields.

### 8.3 Numbers and timestamps

- Personal rating values are JSON integers from 1 through 10.
- Counts and distribution buckets are non-negative integers.
- Aggregate mean is rounded to one decimal using `half up`.
- When count is zero, mean is `null`.
- JSON uses a decimal point; the Spanish UI formats a decimal comma.
- The API never appends `/10` to a number.
- Instants use RFC 3339 `date-time` strings in UTC with `Z`.
- Calendar-only release dates follow section 11.

## 9. Pagination

The bounded MVP uses one-based page pagination.

| Parameter | Type | Default | Rule |
|---|---|---:|---|
| `page` | integer | `1` | Minimum `1`. |
| `pageSize` | integer | `20` | Minimum `1`, maximum `100`. |

Page metadata:

| Field | Meaning |
|---|---|
| `number` | Current one-based page. |
| `size` | Effective page size. |
| `totalItems` | Matches after authorization, filters, and search. |
| `totalPages` | Ceiling of total divided by size; zero when empty. |

Rules:

- Scope, filters, search, and sorting precede pagination.
- Every collection uses `gameId` as final deterministic tie-breaker.
- No matches and a page beyond the last return `200` with `items: []`.
- Invalid values return `422` with `PAGINATION_INVALID`.
- A future cursor contract requires a separate compatibility decision.

## 10. Filtering, search, and sorting

### 10.1 Releases

`GET /api/v1/releases` accepts:

| Parameter | Required | Values |
|---|---:|---|
| `view` | Yes | `recent`, `upcoming` |
| `platformId` | No | Supported opaque product identifier |
| `regionId` | No | Supported opaque product identifier |
| `page` | No | Pagination convention |
| `pageSize` | No | Pagination convention |

The application derives the evaluation date and window using `Europe/Madrid`. The
response makes them explicit:

```json
{
  "view": "upcoming",
  "evaluatedOn": "2026-07-30",
  "window": {
    "from": "2026-07-30",
    "to": "2026-12-31"
  },
  "activeFilters": {
    "platformId": null,
    "regionId": null
  },
  "availableFilters": {
    "platforms": [
      {
        "platformId": "platform_opaque",
        "name": "PlayStation 5"
      }
    ],
    "regions": [
      {
        "regionId": "region_opaque",
        "name": "Europe"
      }
    ]
  },
  "items": [],
  "page": {
    "number": 1,
    "size": 20,
    "totalItems": 0,
    "totalPages": 0
  }
}
```

`availableFilters` uses product taxonomies only. It avoids a separate taxonomy
endpoint until another use case needs one.

Default order:

- `recent`: most recent effective release period first;
- `upcoming`: earliest effective release period first;
- ties: `canonicalTitle` ascending, then `gameId`.

Ordering may use represented period boundaries internally but never exposes an
invented date. Window lengths remain configuration documented with OpenAPI.

### 10.2 Catalogue search

`GET /api/v1/games` requires `q` and accepts pagination.

The minimum search contract:

- trims outer whitespace;
- rejects blank input;
- accepts 1 through 100 Unicode code points after trimming;
- matches without altering stored display titles;
- is case-insensitive and diacritic-insensitive;
- searches canonical titles and approved aliases;
- requires every whitespace-separated token to match;
- does not use fuzzy matching, stemming, translation, or provider search;
- returns bounded-catalogue games only.

Ranking:

1. exact canonical-title match;
2. exact alias match;
3. canonical-title token or prefix match;
4. alias token or prefix match;
5. remaining deterministic contains matches.

Ties use `canonicalTitle`, then `gameId`. Multiple games are returned independently;
the API never resolves ambiguous text arbitrarily.

### 10.3 `Mis puntuaciones`

`GET /api/v1/me/ratings` accepts:

| Parameter | Required | Rule |
|---|---:|---|
| `q` | No | Search current user's rated games only. |
| `sort` | No | `updatedAt`, `canonicalTitle`, `ratingValue`; default `updatedAt`. |
| `direction` | No | `asc`, `desc`; default `desc` for `updatedAt`, otherwise `asc`. |
| `page` | No | Pagination convention. |
| `pageSize` | No | Pagination convention. |

Authenticated scope is applied before search, sorting, total count, or pagination.
`gameId` is the final tie-breaker.

Unknown values and unknown parameter names are rejected, not ignored.

## 11. Release-date representation

Date value and precision form a closed `oneOf` union in OpenAPI.

```json
{
  "precision": "day",
  "value": "2025-06-26"
}
```

```json
{
  "precision": "month",
  "value": "2027-06"
}
```

```json
{
  "precision": "quarter",
  "value": "2027-Q2"
}
```

```json
{
  "precision": "year",
  "value": "2028"
}
```

```json
{
  "precision": "unknown",
  "value": null
}
```

Rules:

- `day` uses `YYYY-MM-DD`;
- `month` uses `YYYY-MM`;
- `quarter` uses `YYYY-Q1` through `YYYY-Q4`;
- `year` uses four-digit `YYYY`;
- `unknown` requires `value: null`;
- partial dates are never completed with invented values;
- presentation strings do not replace structured values;
- tests cover every valid variant and invalid cross-combination.

## 12. Product representations

### 12.1 Game summary and details

A summary contains:

```text
gameId
slug
canonicalTitle
matchedAlias when relevant
primaryCover
concise release context
```

Public game details contain:

```text
gameId
slug
canonicalTitle
aliases needed for display
editorial or sourced summary with provenance
primaryCover
releases
ratingEligibility
ratingStatistics
```

They do not contain the current user's rating. The authenticated client reads
`/api/v1/me/ratings/{gameId}`.

### 12.2 Release

Each release preserves one coherent tuple:

```text
releaseId
gameId
platform
region
releaseDate
status
provenance
providerUpdatedAt
lastSyncedAt
lastVerifiedAt
verificationLevel
reviewStatus
freshnessStatus
```

Fields from different provider records are never merged. Unknown states remain
explicit.

### 12.3 Rating eligibility

```json
{
  "eligible": false,
  "reason": "RELEASE_NOT_OCCURRED",
  "evaluatedOn": "2026-07-30"
}
```

Stable reasons:

```text
ELIGIBLE_RELEASE_FOUND
NO_COMMERCIAL_RELEASE
RELEASE_NOT_OCCURRED
RELEASE_CANCELLED
RELEASE_DATE_UNCERTAIN
RELEASE_REVIEW_REQUIRED
```

Ineligibility is valid read state. It becomes `RATING_NOT_ELIGIBLE` only when a
create or update command is attempted.

### 12.4 Rating statistics

Available with no ratings:

```json
{
  "status": "available",
  "mean": null,
  "count": 0,
  "distribution": {
    "1": 0,
    "2": 0,
    "3": 0,
    "4": 0,
    "5": 0,
    "6": 0,
    "7": 0,
    "8": 0,
    "9": 0,
    "10": 0
  }
}
```

Unavailable:

```json
{
  "status": "unavailable",
  "reasonCode": "RATING_STATISTICS_READ_FAILED"
}
```

`available` requires count and all buckets. `unavailable` omits mean, count, and
distribution. Personal rating never appears inside aggregate statistics.

### 12.5 Cover presentation

Provider:

```json
{
  "kind": "provider",
  "url": "https://images.igdb.com/igdb/image/upload/t_cover_big/co1234.webp",
  "alternativeText": "Carátula de Juego de ejemplo",
  "attribution": {
    "label": "IGDB",
    "sourceUrl": "https://www.igdb.com/games/example-game"
  }
}
```

Fallback:

```json
{
  "kind": "fallback",
  "url": "/assets/covers/fallback.svg",
  "alternativeText": "Carátula oficial no disponible",
  "attribution": null
}
```

Rules:

- the backend constructs URLs from allowlisted metadata;
- arbitrary provider hosts are rejected;
- source links are validated against the approved provider web host;
- provider display requires attribution and a matching source link;
- URLs contain no credentials, tokens, user IDs, or authenticated API paths;
- pending, unavailable, or failed provider references return the fallback;
- provider `image_id`, review internals, and image binaries are not public fields;
- the product does not proxy, persist, or redistribute provider binaries.

## 13. Personal rating and concurrency

### 13.1 Representation

```json
{
  "gameId": "game_opaque",
  "value": 9,
  "createdAt": "2026-07-30T10:15:30Z",
  "updatedAt": "2026-07-30T10:15:30Z",
  "entityTag": "\"rating-version-opaque\""
}
```

`entityTag` is an opaque strong tag for the next `If-Match`. Collection items include
it for direct edit and delete. Individual reads and successful writes also return
the same value in the `ETag` header.

Clients do not decode or alter entity tags.

### 13.2 Concurrency and replay

- Create uses `If-None-Match: *`.
- Update and delete require `If-Match`.
- A stale tag never overwrites the winning state.
- A `412` tells the client to refresh before retrying.
- Database locking and version columns remain internal.
- Concurrent requests preserve at most one active rating per user and game.

The MVP exposes no public `Idempotency-Key` header. Instead:

- conditional `PUT` and `DELETE` use standard HTTP semantics;
- authentication return context contains a single-use nonce or internal idempotency
  reference;
- the BFF atomically consumes it or applies equivalent replay-safe idempotency;
- browser code does not automatically retry an ambiguous rating command;
- after an ambiguous failure, the client reads current state first;
- callback reload or replay never repeats a logical rating confirmation.

A future external-consumer API may add a separate idempotency-key contract.

## 14. Error format

### 14.1 Problem Details

Errors with bodies use RFC 9457:

```http
Content-Type: application/problem+json
```

```json
{
  "type": "urn:videogame-platform:problem:rating-not-eligible",
  "title": "Rating is not eligible",
  "status": 422,
  "detail": "The game does not currently have qualifying release evidence.",
  "instance": "urn:videogame-platform:problem-instance:opaque",
  "code": "RATING_NOT_ELIGIBLE",
  "category": "business_rule",
  "correlationId": "correlation-opaque",
  "eligibilityReason": "RELEASE_NOT_OCCURRED"
}
```

Rules:

- `type` identifies a stable problem type.
- `title` is stable developer-facing text.
- `status` equals the actual HTTP status.
- `detail` is safe guidance and is not parsed by clients.
- `instance` is opaque.
- `code` drives client logic.
- `category` uses the approved error categories.
- `correlationId` matches the response header.
- extensions are documented in OpenAPI.
- Spanish UI copy is selected from code and structured data.
- no stack trace, SQL, provider payload, token, claim, or credential appears.

### 14.2 Validation violations

Malformed JSON returns `400` with `REQUEST_MALFORMED`. A well-formed request that
violates field constraints returns `422` and may include:

```json
{
  "violations": [
    {
      "pointer": "/value",
      "code": "RATING_VALUE_INVALID",
      "message": "Must be an integer from 1 through 10."
    }
  ]
}
```

Clients use `code` and `pointer`, not the mutable message.

### 14.3 Delivery-layer codes

| Code | Meaning |
|---|---|
| `REQUEST_MALFORMED` | JSON, path, or query syntax cannot be parsed. |
| `REQUEST_PROPERTY_UNKNOWN` | A command contains an unsupported property. |
| `REQUEST_PARAMETER_UNKNOWN` | A request contains an unsupported query parameter. |
| `PAGINATION_INVALID` | Pagination constraints are invalid. |
| `PRECONDITION_REQUIRED` | Rating preconditions are missing or contradictory. |
| `CSRF_VALIDATION_FAILED` | A state-changing request lacks valid CSRF proof. |
| `REQUEST_TOO_LARGE` | The request exceeds the configured limit. |
| `MEDIA_TYPE_UNSUPPORTED` | Request media type is unsupported. |
| `REPRESENTATION_NOT_ACCEPTABLE` | No supported response type is acceptable. |
| `METHOD_NOT_ALLOWED` | The target does not support the method. |
| `RATE_LIMIT_EXCEEDED` | An edge abuse limit rejected the request. |
| `INTERNAL_ERROR` | No more specific safe code exists. |

These codes never replace a more specific domain or application error.

## 15. HTTP status mapping

### 15.1 Success

| Status | Use |
|---:|---|
| `200 OK` | Query, update, delete with result, session read, or empty collection. |
| `201 Created` | Rating created; includes `Location` and `ETag`. |
| `304 Not Modified` | Conditional public `GET`; no body. |

The API does not return `202` for synchronous work or `204` when a result is needed.

### 15.2 Client and domain errors

| Status | Codes or condition |
|---:|---|
| `400 Bad Request` | `REQUEST_MALFORMED`; `RETURN_CONTEXT_INVALID` when represented as an API error |
| `401 Unauthorized` | `AUTHENTICATION_REQUIRED`; invalid or expired session |
| `403 Forbidden` | `CSRF_VALIDATION_FAILED` |
| `404 Not Found` | `GAME_NOT_FOUND`; scoped `RATING_NOT_FOUND` |
| `405 Method Not Allowed` | `METHOD_NOT_ALLOWED`; include `Allow` |
| `406 Not Acceptable` | `REPRESENTATION_NOT_ACCEPTABLE` |
| `409 Conflict` | `RETURN_CONTEXT_REPLAYED` |
| `412 Precondition Failed` | `RATING_ALREADY_EXISTS`; `RATING_WRITE_CONFLICT` |
| `413 Content Too Large` | `REQUEST_TOO_LARGE` |
| `415 Unsupported Media Type` | `MEDIA_TYPE_UNSUPPORTED` |
| `422 Unprocessable Content` | `RATING_VALUE_INVALID`, `RATING_NOT_ELIGIBLE`, `SEARCH_QUERY_INVALID`, `FILTER_INVALID`, `PLATFORM_NOT_SUPPORTED`, `REGION_NOT_SUPPORTED`, `SORT_INVALID`, `PAGINATION_INVALID`, unknown request members, and blocking `RELEASE_DATA_REVIEW_REQUIRED` |
| `428 Precondition Required` | `PRECONDITION_REQUIRED` |
| `429 Too Many Requests` | `RATE_LIMIT_EXCEEDED`; include `Retry-After` when known |

Review-required release data is explicit on reads and an error only when it blocks a
command.

### 15.3 Server errors

| Status | Codes or condition |
|---:|---|
| `500 Internal Server Error` | `RATING_WRITE_FAILED`, `PERSONAL_RATINGS_READ_FAILED`, `INTERNAL_ERROR` |
| `503 Service Unavailable` | `CATALOGUE_NOT_READY`, `CATALOGUE_READ_FAILED`; use `Retry-After` only when useful |

`RATING_STATISTICS_READ_FAILED` normally appears as unavailable aggregate state
inside a successful game response. It becomes top-level `500` only when no coherent
game response can be constructed.

### 15.4 Codes outside the browser API

These approved synchronization and curation codes receive no browser HTTP mapping:

```text
PROVIDER_AUTHENTICATION_FAILED
PROVIDER_RATE_LIMITED
PROVIDER_UNAVAILABLE
PROVIDER_RESPONSE_INVALID
PROVIDER_MAPPING_FAILED
EXTERNAL_REFERENCE_CONFLICT
RELEASE_DATA_INVALID
COVER_REFERENCE_INVALID
SYNCHRONIZATION_WRITE_FAILED
```

`GAME_OUTSIDE_CATALOGUE` and `ALIAS_AMBIGUOUS` remain vocabulary for exact
resolution and curation. Search returns zero or multiple results as valid outcomes.

## 16. Authentication, session, and CSRF

### 16.1 Session boundary

The BFF is the confidential OAuth client. It maps validated `issuer + subject` to a
stable product `UserId` and keeps tokens server-side.

The browser receives:

```text
name: __Host-vgp_session
Secure
HttpOnly
SameSite=Lax
Path=/
no Domain attribute
```

Session persistence and encryption remain internal.

### 16.2 Session resource

`GET /api/v1/session` returns `200` for anonymous and authenticated browsers.

Anonymous:

```json
{
  "authenticated": false
}
```

Authenticated:

```json
{
  "authenticated": true,
  "csrfToken": "opaque-csrf-token"
}
```

It exposes no token, issuer, subject, email, role, or internal `UserId`. Additional
profile data requires a product use case. Session responses use
`Cache-Control: no-store`.

### 16.3 CSRF

- Every authenticated `PUT`, `POST`, `PATCH`, or `DELETE` requires
  `X-CSRF-Token`.
- Logout requires the same proof.
- The token is session-bound and not persisted in browser storage.
- Invalid proof returns `403` with `CSRF_VALIDATION_FAILED`.
- SameSite is defense in depth, not the only control.
- The BFF validates `Origin` and relevant fetch metadata where supported.
- CORS is disabled by default; no credentialed cross-origin consumer is approved.

The token algorithm remains internal.

### 16.4 Authentication failures

- Protected API requests without a valid session return `401`.
- Fetch calls are not redirected to the identity provider.
- The frontend explicitly starts authentication navigation.
- `403` is reserved for a known authenticated principal without a capability.
- Current rating ownership normally uses scoped `RATING_NOT_FOUND`.

BFF navigation outcomes:

| Outcome | Stable code | Browser behaviour |
|---|---|---|
| Provider authentication fails | `AUTHENTICATION_FAILED` | Return safely with no rating change. |
| User cancels | `AUTHENTICATION_CANCELLED` | Return to the game with no server-side change. |
| Return context is invalid | `RETURN_CONTEXT_INVALID` | Discard it and use an allowlisted destination. |
| Return context expired | `RETURN_CONTEXT_EXPIRED` | Require a new confirmation. |
| Return context was consumed | `RETURN_CONTEXT_REPLAYED` | Do not repeat the command; refresh state. |

The BFF carries outcomes through server-side state or a short-lived opaque reference.
Redirect URLs contain no provider error, token, rating value, or identity data.

## 17. Caching

- Public catalogue and game `GET` responses declare `Cache-Control`.
- Public responses may use `ETag` and `If-None-Match`.
- Personal, session, and command responses use `Cache-Control: no-store`.
- Responses with `Set-Cookie` are not public cacheable representations.
- Public game details never contain personal rating state.
- Personal or session problems use `no-store`.
- Cover binaries load directly from the approved CDN or product origin.
- Cache keys never use an unvalidated client `UserId`.

Exact public cache durations depend on synchronization freshness configuration.

## 18. Correlation and observability

- Every API response includes `X-Correlation-ID`.
- A valid inbound value may be propagated; otherwise one is created.
- W3C `traceparent` is propagated when tracing is enabled.
- Problem `correlationId` matches the response header.
- Logs record method, route template, status, duration, outcome, and correlation.
- Logs exclude cookies, CSRF tokens, OAuth tokens, raw subjects, return context,
  credentials, and sensitive bodies.
- Metrics use route templates rather than raw high-cardinality paths.
- Stable error and degraded-state outcomes remain observable.

## 19. Security and privacy

- Validate path, query, header, and body input at the boundary.
- Apply request-size limits before large deserialization.
- Do not bind JSON to persistence or provider DTOs.
- Reject arbitrary returns and redirects.
- CSP allowlists the approved IGDB image host and product assets.
- Referrer policy avoids sending navigation or user identifiers to covers.
- Credentials, tokens, identities, ratings, and auth context never enter URLs.
- Do not expose exception text or provider failures.
- Do not reveal whether another user rated a game.
- Rate limiting is added only for observed abuse or an explicit experiment.
- Product authorization always remains in the backend.

## 20. Compatibility

### 20.1 Compatible within `v1`

- Add an optional response field.
- Add an endpoint without changing existing semantics.
- Add an optional request parameter with a safe default.
- Add a genuinely new documented problem code.
- Relax an input constraint without weakening a domain invariant.

Clients ignore unknown response properties.

### 20.2 Breaking

- Remove or rename a path, method, field, enum, or error code.
- Change type, meaning, required state, or nullability.
- Change pagination semantics incompatibly.
- Change authentication or status-code semantics.
- Reuse a code for another meaning.
- Replace product identity with provider identity.

A breaking change requires a new major version or controlled dual-support migration.

### 20.3 Deprecation

- Mark OpenAPI elements deprecated.
- Document replacement and removal condition.
- Measure remaining use.
- Define migration and rollback.
- Do not remove until the approved support condition is met.

The private MVP does not need a broad public deprecation policy yet.

## 21. OpenAPI authoring rules

Create the first contract at:

```text
docs/architecture/api/openapi.yaml
```

Use OpenAPI `3.1.2`. OpenAPI `3.2.0` exists, but 3.2-only features remain deferred
until the selected validator, generator, mock, and implementation toolchain
demonstrate support.

The contract must:

- define environment servers without credentials;
- use `lowerCamelCase` operation IDs;
- use stable tags such as `Catalogue`, `Ratings`, and `Session`;
- reuse identifiers, dates, covers, ratings, pagination, problems, headers, and
  security schemes through `components`;
- enforce JSON Schema constraints;
- model release date and statistics variants with `oneOf`;
- define every response, header, and media type;
- include provider-independent success, empty, degraded, validation,
  authentication, conflict, and technical examples;
- model the opaque BFF cookie security scheme;
- require CSRF on protected state changes;
- reject unknown command properties;
- avoid generated-class or framework schema names;
- pass automated lint and schema validation;
- drive or validate contract tests.

Generated code is disposable. The reviewed OpenAPI source remains the contract.

## 22. Deferred implementation decisions

- frontend and backend frameworks;
- database product and persistence mapping;
- identity-provider product;
- session persistence and CSRF algorithm;
- exact public cache durations;
- release-window lengths and stale thresholds;
- database locking or version columns;
- physical versus logical rating deletion;
- aggregate calculation versus materialization;
- synchronization cadence, retry values, and operator interface;
- hosting, TLS termination, telemetry backend, and secret manager;
- API Management product or deployment;
- public external-consumer policy.

OpenAPI must not assume these choices when they do not affect the public contract.

## 23. Review checklist

- [ ] `/api/v1` is accepted as the initial base path.
- [ ] Public game data and personal rating data remain separate resources.
- [ ] The resource map covers the complete MVP without extra scope.
- [ ] Conditional `PUT` with `If-None-Match` and `If-Match` is accepted.
- [ ] Delete returns updated aggregate state in `200`.
- [ ] One-based pagination with default `20` and maximum `100` is accepted.
- [ ] Accent-insensitive, non-fuzzy search is accepted.
- [ ] Domain distinctions remain explicit in every representation.
- [ ] RFC 9457 Problem Details and status mapping are accepted.
- [ ] Session cookie, session resource, CSRF header, and no-CORS default are accepted.
- [ ] No public idempotency-key header is required for the same-origin MVP.
- [ ] Personal and session responses remain `no-store`.
- [ ] OpenAPI 3.1.2 is accepted initially.
- [ ] Deferred choices remain outside the contract.
- [ ] Product, provider, and release-mode scope has not expanded.

The document remains `Draft` until the owner explicitly accepts or changes these
items.

## 24. Traceability

| Convention area | Source |
|---|---|
| Product identity | Domain `GAME-001`, `RAT-010`, `EXT-001`; common use-case outputs |
| Bounded local catalogue | Domain `CAT-001`–`CAT-003`; `UC-001`–`UC-003`; ADR-0004 |
| Release precision | Domain `REL-003`, `REL-004`, `MD-002` |
| Trusted time | Domain `REL-011`, `MD-009`; rating and release use cases |
| Rating identity and ownership | Domain `RAT-002`, `RAT-004`, `USR-001`; `UC-005`–`UC-008` |
| Personal/aggregate separation | Domain `RAT-009`, `AGG-001`–`AGG-005` |
| Authentication replay | `UC-004`; ADR-0003 |
| Errors | Domain section 14; use-case section 7 |
| Covers | Domain `GAME-006`–`GAME-015`, `MD-013`, `MD-014`; ADR-0001 |
| Provider isolation | Domain `EXT-001`–`EXT-004`; `UC-009`; ADR-0004 |
| API boundary | Solution `SA-002`, `SA-003`, `SA-005`, `SA-012`; ADR-0003 |

## 25. Standards references

- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/info/rfc9110/)
- [RFC 9111: HTTP Caching](https://www.rfc-editor.org/info/rfc9111/)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/info/rfc9457/)
- [RFC 6585: Additional HTTP Status Codes](https://www.rfc-editor.org/info/rfc6585/)
- [RFC 9700: OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/info/rfc9700/)
- [RFC 10017: OAuth 2.0 for Browser-Based Applications](https://auth48-transition.rfc-editor.org/authors/rfc10017.html)
- [OpenAPI Specification versions](https://spec.openapis.org/oas/)

## 26. Change history

| Date | Version | Change | Owner |
|---|---|---|---|
| 2026-07-30 | 0.1 | Initial draft derived from the approved domain, application, solution architecture, and ADR-0001 through ADR-0004. | Ruben Hernandez |
