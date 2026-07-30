# Learning MVP use cases and relevant errors

- **Status:** Approved
- **Version:** 1.0
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-30
- **Approval:** Owner-approved for the private, non-commercial learning MVP
- **Phase:** 1 — MVP solution definition
- **Scope:** Learning MVP
- **Product Brief:** [Product Brief](../../product/product-brief.md)
- **Story map:** [Learning MVP story map](../../product/mvp-story-map.md)
- **Domain model:** [Learning MVP domain model](../domain/mvp-domain-model.md)
- **Provider spike:** [Game-data-provider spike](../../research/game-data-providers-spike.md)
- **Cover decision:** [ADR-0001: Reference IGDB cover images without copying binaries](../../decisions/0001-reference-igdb-cover-images.md)

> This document defines the minimum application use cases and the most relevant
> failure outcomes for the approved learning MVP. It is an application contract:
> it does not define HTTP endpoints, transport payloads, database tables, framework
> classes, screens, or deployment architecture.

## 1. Purpose

The document translates the approved journey and domain rules into operations that
an application layer must coordinate consistently.

It supports one complete vertical slice:

```text
release discovery
    -> game page
    -> personal rating
    -> Mis puntuaciones
```

It provides a common basis for:

- API design;
- application services or command/query handlers;
- authorization boundaries;
- frontend integration;
- acceptance and automated tests;
- error handling and observability.

## 2. Scope

### 2.1 Included

- Browse recent and upcoming releases from the bounded catalogue.
- Filter releases by normalized platform and region.
- Search games by canonical title or alias.
- Retrieve a game page with release, cover, provenance, freshness, verification,
  aggregate-rating, and personal-rating context.
- Require authentication only when a user confirms a rating or accesses personal
  ratings.
- Resume the same game context after successful authentication.
- Create, update, and delete one active personal rating per user and game.
- Retrieve, search, and sort `Mis puntuaciones` for the authenticated user.
- Refresh normalized metadata for existing bounded-catalogue members from IGDB and
  keep import candidates outside the domain pending explicit curation.
- Preserve useful local behaviour when IGDB or its image CDN is unavailable.

### 2.2 Deferred

- Written reviews, comments, reactions, and moderation.
- Personal library, play status, wishlists, and custom lists.
- Recommendations, rankings, and affinity profiles.
- Professional reviews and external scores.
- Prices, stores, and purchase comparison.
- Platform-specific personal ratings or rating aggregates.
- Multiple simultaneous catalogue providers.
- Broad unattended catalogue ingestion.
- Administrative interfaces for curation and reconciliation.

## 3. Actors and boundaries

| Actor or system | Responsibility in these use cases |
|---|---|
| `Visitor` | Browses releases, searches the bounded catalogue, and reads game pages. |
| `AuthenticatedUser` | Performs all visitor actions and manages their own ratings. |
| `IdentityProvider` | Authenticates the user and supplies a stable `UserId`; its internal protocol is outside the domain. |
| `CatalogueProvider` | Supplies IGDB catalogue metadata to the backend synchronization boundary. |
| `SchedulerOrOperator` | Starts a bounded catalogue synchronization manually or on an operational schedule. |
| `Application` | Coordinates domain policies, repositories, authorization, provider adapters, transactions, and telemetry. |

The client never supplies a trusted user identity. Rating ownership and
`Mis puntuaciones` scope always use the authenticated principal.

## 4. Use-case conventions

### 4.1 Commands and queries

- A **command** requests a state change and may be rejected by domain or
  authorization rules.
- A **query** reads current product state and must not change domain state.
- Commands affecting a rating preserve the previous valid rating when the operation
  is invalid or fails.
- Queries read locally normalized catalogue data; they do not call IGDB in the
  request path.

### 4.2 Common output rules

- Product identifiers are internal `GameId` values, never provider IDs.
- Personal and aggregate ratings are returned as separate, explicitly labelled
  concepts.
- Date value and precision remain coherent and are never made artificially more
  precise.
- Unknown, stale, or review-required data remains explicit.
- A catalogue-visible game always resolves to an approved cover or the
  product-owned fallback.
- Spanish presentation formatting belongs to the delivery layer, including decimal
  comma and no `/10` denominator.

### 4.3 Common failure rules

- Every rejected operation returns a stable error code suitable for client logic and
  automated tests.
- User-facing Spanish messages are produced by the delivery layer and may evolve
  independently from stable codes.
- Technical failures expose a correlation or trace identifier but never credentials,
  tokens, raw provider payloads, stack traces, or internal persistence details.
- Zero results, no ratings, an unavailable aggregate, stale-but-usable data, and a
  fallback cover are valid product states rather than technical errors.

## 5. Use-case catalogue

| ID | Use case | Type | Primary actor |
|---|---|---|---|
| `UC-001` | Browse recent or upcoming releases | Query | Visitor |
| `UC-002` | Search the bounded catalogue | Query | Visitor |
| `UC-003` | View game details | Query | Visitor |
| `UC-004` | Authenticate and resume rating context | Boundary flow | Visitor |
| `UC-005` | Create personal rating | Command | AuthenticatedUser |
| `UC-006` | Update personal rating | Command | AuthenticatedUser |
| `UC-007` | Delete personal rating | Command | AuthenticatedUser |
| `UC-008` | View, search, and sort `Mis puntuaciones` | Query | AuthenticatedUser |
| `UC-009` | Synchronize the bounded catalogue | Command/workflow | SchedulerOrOperator |

## 6. Detailed use cases

### UC-001 — Browse recent or upcoming releases

**Goal:** Show catalogue-visible releases relevant to a selected time view, with
optional platform and region filters.

**Preconditions:** None beyond application availability.

**Inputs:**

- release view: `recent` or `upcoming`;
- evaluation date derived by the application from its trusted clock and configured
  product zone, never supplied by the client;
- optional `platformId`;
- optional `regionId`;
- pagination parameters defined later by the API contract.

**Main flow:**

1. Validate the requested view and filters.
2. Query only releases whose games belong to `BoundedCatalogue`.
3. Apply platform and region filters when supplied.
4. Preserve release status, date precision, provenance, verification, review, and
   freshness information.
5. Resolve each game to its approved cover or product-owned fallback.
6. Return a deterministic page of results and the active filters.

**Valid alternative outcomes:**

- No releases match: return an explicit empty result.
- Local data is stale: return usable data with its freshness state.
- A provider cover cannot be resolved: return the fallback without removing the
  game.

**Relevant errors:**

- `FILTER_INVALID`
- `PLATFORM_NOT_SUPPORTED`
- `REGION_NOT_SUPPORTED`
- `CATALOGUE_NOT_READY`
- `CATALOGUE_READ_FAILED`

**Key invariants:** `CAT-001`, `CAT-003`, `GAME-006`–`GAME-015`, `REL-002`–`REL-004`,
`REL-008`–`REL-011`, `EXT-003`.

---

### UC-002 — Search the bounded catalogue

**Goal:** Find supported games using canonical or alternative titles without
exposing raw provider results.

**Preconditions:** None beyond application availability.

**Inputs:**

- non-blank search query;
- pagination or result limit defined by the API contract.

**Main flow:**

1. Normalize the query according to the future search contract without changing
   stored display titles.
2. Search canonical titles and supported aliases.
3. Restrict results to curated games in `BoundedCatalogue`.
4. Return internal game identity, canonical title, relevant match context, cover,
   and concise release context.

**Valid alternative outcomes:**

- No game matches: return zero results.
- Multiple games match the same text or alias: return each matching internal game as
  a distinct result without selecting one arbitrarily.
- A plausible title is outside the bounded catalogue: return zero results with the
  catalogue-boundary explanation. The query does not call a provider or infer that
  a particular external game exists.

**Relevant errors:**

- `SEARCH_QUERY_INVALID`
- `CATALOGUE_NOT_READY`
- `CATALOGUE_READ_FAILED`

**Key invariants:** `CAT-001`–`CAT-003`, `GAME-001`–`GAME-005`, `GAME-006`–`GAME-015`,
`EXT-001`, `EXT-002`.

---

### UC-003 — View game details

**Goal:** Retrieve the complete MVP context required to understand a supported game
and decide whether it can be rated.

**Preconditions:** The supplied identifier is an internal `GameId`.

**Inputs:**

- `gameId`;
- optional authenticated principal;
- evaluation date derived by the application from its trusted clock and configured
  product zone, never supplied by the client.

**Main flow:**

1. Load the supported game by internal identity.
2. Load its coherent release tuples.
3. Evaluate rating eligibility using `RatingEligibilityPolicy` and the explicit
   evaluation date.
4. Load aggregate rating statistics.
5. When authenticated, load the current user's active personal rating for the game.
6. Resolve the approved provider cover or fallback, with attribution data when the
   provider cover is used.
7. Return game, release, provenance, freshness, verification, review, aggregate,
   personal-rating, and eligibility context as distinct fields.

**Valid alternative outcomes:**

- No active ratings exist: return `count = 0`, all distribution buckets at zero, and
  no numeric mean.
- Aggregate statistics cannot be read: return the otherwise coherent game page with
  an explicit unavailable aggregate state and the stable failure code. Fail the
  complete query only when no coherent game response can be constructed.
- The game is not eligible for rating: return the stable eligibility reason and a
  disabled rating state.
- Release data requires review: expose that state and do not use that release as
  proof of eligibility.
- The cover cannot be resolved: return the product-owned fallback.

**Relevant errors:**

- `GAME_NOT_FOUND`
- `RELEASE_DATA_REVIEW_REQUIRED` only when the requested operation cannot safely
  continue; otherwise it is returned as explicit data quality state.
- `CATALOGUE_NOT_READY`
- `CATALOGUE_READ_FAILED`
- `RATING_STATISTICS_READ_FAILED`

**Key invariants:** `GAME-001`–`GAME-015`, `REL-001`–`REL-011`, `RAT-009`,
`AGG-001`–`AGG-005`, `EXT-001`–`EXT-003`.

---

### UC-004 — Authenticate and resume rating context

**Goal:** Authenticate only when the visitor confirms a rating and return them to
that same game context.

**Trigger:** An unauthenticated visitor chooses a valid 1–10 value and confirms it.

**Inputs retained before redirection:**

- internal `gameId`;
- selected rating value;
- approved return context or opaque state reference;
- a single-use nonce or idempotency reference;
- anti-forgery and expiry information defined by the identity integration.

**Main flow:**

1. Validate the rating input sufficiently to avoid starting authentication for an
   impossible request.
2. Store or encode a short-lived, tamper-resistant return context.
3. Redirect to the established identity flow.
4. On success, obtain a stable authenticated `UserId`.
5. Validate and atomically consume the return context, or apply equivalent
   replay-safe idempotency.
6. Resume the same game context.
7. Execute `UC-005` exactly once for the logical confirmation, using the
   authenticated principal and retained rating value.

**Alternative outcomes:**

- Authentication is cancelled or fails: return to the game without creating a
  rating and preserve the selected value only when safe and useful.
- Return context is expired or invalid: return to a safe game or release view and
  require the user to confirm the rating again.
- Return context was already consumed: do not execute the rating command again;
  return the current game and rating state.
- The game becomes ineligible before completion: reject rating creation using the
  current eligibility decision.

**Relevant errors:**

- `AUTHENTICATION_REQUIRED`
- `AUTHENTICATION_FAILED`
- `AUTHENTICATION_CANCELLED`
- `RETURN_CONTEXT_INVALID`
- `RETURN_CONTEXT_EXPIRED`
- `RETURN_CONTEXT_REPLAYED`
- any error from `UC-005`

**Security requirements:**

- Never trust a user ID from the client or return context.
- Do not place credentials, access tokens, or sensitive personal data in URLs or
  logs.
- Validate the destination against an allowlist to prevent open redirects.
- A callback retry, browser reload, or replay never executes the logical rating
  confirmation more than once.

---

### UC-005 — Create personal rating

**Goal:** Create the user's first active rating for an eligible game.

**Preconditions:**

- The actor is authenticated.
- No active rating exists for `UserId + GameId`.

**Inputs:**

- authenticated `UserId` from the security boundary;
- internal `gameId`;
- integer `ratingValue`;
- evaluation date derived by the application from its trusted clock and configured
  product zone, never supplied by the client.

**Main flow:**

1. Validate that `ratingValue` is an integer from 1 to 10.
2. Load the supported game and its release evidence.
3. Evaluate `RatingEligibilityPolicy`.
4. Reject the command when the game is not eligible.
5. Create one active rating identified by `UserId + GameId`.
6. Recalculate or update aggregate statistics from active ratings.
7. Commit personal and aggregate changes atomically from the product perspective.
8. Return the created personal rating and current aggregate context separately.
9. Record success or rejection telemetry without logging sensitive data.

**Relevant errors:**

- `AUTHENTICATION_REQUIRED`
- `GAME_NOT_FOUND`
- `RATING_VALUE_INVALID`
- `RATING_NOT_ELIGIBLE`, including a stable eligibility reason
- `RATING_ALREADY_EXISTS`
- `CATALOGUE_NOT_READY`
- `CATALOGUE_READ_FAILED`
- `RATING_WRITE_FAILED`

**Failure guarantee:** No active rating or aggregate contribution is created when
any validation, authorization, eligibility, or persistence step fails.

**Key invariants:** `RAT-001`–`RAT-003`, `RAT-005`, `RAT-008`–`RAT-010`,
`AGG-001`–`AGG-005`.

---

### UC-006 — Update personal rating

**Goal:** Change the authenticated user's existing rating while preserving one active
rating.

**Preconditions:**

- The actor is authenticated.
- An active rating exists for the authenticated `UserId + GameId`.

**Inputs:**

- authenticated `UserId`;
- internal `gameId`;
- new integer `ratingValue`;
- evaluation date derived by the application from its trusted clock and configured
  product zone, never supplied by the client.

**Main flow:**

1. Load the rating using authenticated `UserId + GameId`.
2. Treat absence in that authenticated scope as `RATING_NOT_FOUND`; do not inspect
   or reveal whether another user has rated the game.
3. Validate the new rating value.
4. Re-evaluate game eligibility.
5. Update the existing active rating rather than creating another.
6. Recalculate or update aggregate statistics.
7. Commit the personal and aggregate change atomically from the product perspective.
8. Return the updated personal rating and aggregate context separately.

**Relevant errors:**

- `AUTHENTICATION_REQUIRED`
- `RATING_NOT_FOUND`
- `RATING_VALUE_INVALID`
- `RATING_NOT_ELIGIBLE`
- `CATALOGUE_NOT_READY`
- `CATALOGUE_READ_FAILED`
- `RATING_WRITE_CONFLICT`
- `RATING_WRITE_FAILED`

**Failure guarantee:** The previous valid rating and its aggregate contribution
remain unchanged after any rejected or failed update.

**Key invariants:** `RAT-001`, `RAT-002`, `RAT-004`–`RAT-006`, `RAT-008`–`RAT-010`,
`AGG-001`–`AGG-005`.

---

### UC-007 — Delete personal rating

**Goal:** Remove the authenticated user's active rating from personal and aggregate
contexts.

**Preconditions:**

- The actor is authenticated.
- An active rating exists for the authenticated `UserId + GameId`.

**Inputs:**

- authenticated `UserId`;
- internal `gameId`.

**Main flow:**

1. Load the rating using authenticated `UserId + GameId`.
2. Treat absence in that authenticated scope as `RATING_NOT_FOUND`; do not inspect
   or reveal whether another user has rated the game.
3. Remove the active rating according to the later persistence strategy.
4. Remove its contribution from aggregate statistics.
5. Commit personal and aggregate changes atomically from the product perspective.
6. Return a successful no-active-rating state and the updated aggregate context.

**Relevant errors:**

- `AUTHENTICATION_REQUIRED`
- `RATING_NOT_FOUND`
- `RATING_WRITE_CONFLICT`
- `RATING_WRITE_FAILED`

**Special rule:** Current rating eligibility does not block owner deletion.

**Failure guarantee:** A failed delete preserves the existing rating and aggregate
contribution.

**Key invariants:** `RAT-004`, `RAT-007`–`RAT-010`, `AGG-001`–`AGG-005`.

---

### UC-008 — View, search, and sort `Mis puntuaciones`

**Goal:** Retrieve only the authenticated user's active ratings with enough game
context to find, understand, and maintain them.

**Preconditions:** The actor is authenticated.

**Inputs:**

- authenticated `UserId`;
- optional title or alias query;
- optional sort key: `updatedAt`, `canonicalTitle`, or `ratingValue`;
- optional direction: ascending or descending;
- pagination parameters defined by the API contract.

**Main flow:**

1. Scope the query by authenticated `UserId` before any other operation.
2. Load active ratings and their catalogue games.
3. When a query is supplied, search only the scoped user's rated games by canonical
   title or alias.
4. Validate and apply the requested sort, or use `updatedAt` descending by default.
5. Use `gameId` as the final deterministic tie-breaker.
6. Return rating value, timestamps, internal game identity, canonical title, cover,
   and direct game navigation context.

**Valid alternative outcomes:**

- No ratings returns an explicit empty state.
- No rated game matches the query: return zero results.
- Multiple rated games match the same text or alias: return every matching scoped
  rating without selecting one arbitrarily.

**Relevant errors:**

- `AUTHENTICATION_REQUIRED`
- `SEARCH_QUERY_INVALID`
- `SORT_INVALID`
- `CATALOGUE_NOT_READY`
- `PERSONAL_RATINGS_READ_FAILED`

**Key invariants:** `GAME-004`, `GAME-006`–`GAME-015`, `USR-001`, `USR-002`,
`RAT-009`.

---

### UC-009 — Synchronize the bounded catalogue

**Goal:** Refresh locally normalized metadata for already curated catalogue members
from IGDB without making user request paths depend on provider availability or
silently expanding the catalogue.

**Preconditions:**

- The current release mode still permits the approved IGDB usage.
- Backend credentials are available through secret management.
- The publication scope contains only explicitly curated internal games.

**Inputs:**

- curated internal game identities and their typed provider references;
- release window;
- last successful synchronization watermark when available;
- configured provider rate and retry limits;
- explicit provider timestamp mapping zone.

**Main flow:**

1. Authenticate to IGDB from the backend boundary.
2. Fetch only bounded data within controlled rate and retry limits.
3. Map raw transport data defensively into provider-independent candidate data.
4. Preserve provider identity, provenance, update time, date precision, unknown
   values, and coherent release tuples.
5. Reject silent merges, external-reference conflicts, invalid date/precision
   combinations, and availability-as-release mappings.
6. Preserve product-owned Spanish aliases without deriving or overwriting them from
   provider evidence.
7. Build or update cover-reference metadata from `image_id` without copying provider
   binaries. Preserve an existing approval only while its approved reference and
   usage constraints remain unchanged; a new or materially changed provider cover
   becomes `pending_review` and resolves to the product-owned fallback.
8. Mark ambiguous or conflicting release evidence as review-required.
9. Publish only validated updates for existing curated catalogue members.
10. Keep new provider results and import candidates in provider-adapter staging until
    a separate explicit curation decision accepts them; this use case never creates
    a domain `Game` or grants cover approval automatically.
11. Keep the previous valid local data when a record or the whole run cannot be
    safely replaced.
12. Record request count, latency, retries, mapping failures, rate limits, candidate
    outcomes, freshness, and final run status.

**Valid alternative outcomes:**

- A new provider result is discovered: retain it outside the domain as an import
  candidate requiring explicit curation.
- A new or materially changed cover reference requires review: keep the game visible
  with the product-owned fallback.
- A record is ambiguous or invalid: isolate it and preserve the previous valid
  normalized record.

**Relevant errors:**

- `PROVIDER_AUTHENTICATION_FAILED`
- `PROVIDER_RATE_LIMITED`
- `PROVIDER_UNAVAILABLE`
- `PROVIDER_RESPONSE_INVALID`
- `PROVIDER_MAPPING_FAILED`
- `EXTERNAL_REFERENCE_CONFLICT`
- `RELEASE_DATA_INVALID`
- `RELEASE_DATA_REVIEW_REQUIRED`
- `COVER_REFERENCE_INVALID`
- `SYNCHRONIZATION_WRITE_FAILED`

**Failure guarantee:** Provider or synchronization failure does not delete the last
valid local catalogue data, automatically remove a supported game, publish an
uncurated candidate, or approve a provider cover.

**Key invariants:** `CAT-002`, `CAT-003`, `GAME-001`, `GAME-005`–`GAME-015`,
`REL-003`–`REL-011`, `EXT-001`–`EXT-004`.

## 7. Error model

### 7.1 Error categories

| Category | Meaning | Expected client behaviour |
|---|---|---|
| `validation` | Input does not satisfy a syntactic or value constraint. | Correct the input without retrying unchanged. |
| `authentication` | No valid authenticated principal exists. | Start or repeat authentication. |
| `authorization` | The authenticated principal cannot perform the operation. | Do not retry as the same principal without a state change. |
| `not_found` | The requested supported product resource does not exist in scope. | Return to a safe context or refresh local state. |
| `business_rule` | A domain policy rejects an otherwise valid request. | Explain the reason and preserve previous valid state. |
| `conflict` | Current state changed or contradicts the requested transition. | Refresh current state before retrying. |
| `dependency` | An external system failed or returned unusable data. | Use local/fallback behaviour where defined; retry only according to policy. |
| `technical` | Persistence or internal execution failed unexpectedly. | Preserve state, show a generic actionable error, and correlate with telemetry. |

### 7.2 Canonical domain errors used by these use cases

These codes come from the domain model and must not be redefined inconsistently by
API or UI contracts.

| Code | Category | Applies to | Meaning |
|---|---|---|---|
| `GAME_NOT_FOUND` | `not_found` | `UC-003`, `UC-005` | No supported internal game exists for the supplied identity. |
| `RATING_NOT_ELIGIBLE` | `business_rule` | `UC-005`, `UC-006` | Commercial release has not been established under `RatingEligibilityPolicy`. |
| `RATING_VALUE_INVALID` | `validation` | `UC-005`, `UC-006` | The value is not an integer from 1 to 10. |
| `RATING_NOT_FOUND` | `not_found` | `UC-006`, `UC-007` | No active rating exists for the authenticated user and game. |
| `RELEASE_DATA_REVIEW_REQUIRED` | `business_rule` | `UC-003`, `UC-005`, `UC-006`, `UC-009` | Release evidence is ambiguous, conflicting, incomplete, or marked for review. |

`GAME_OUTSIDE_CATALOGUE` and `ALIAS_AMBIGUOUS` remain useful domain vocabulary for
exact resolution and curation workflows. The current search use cases do not emit
them: zero results and multiple text matches are valid query outcomes.

`RATING_NOT_ELIGIBLE` should include one stable reason from the domain policy:

- `NO_COMMERCIAL_RELEASE`;
- `RELEASE_NOT_OCCURRED`;
- `RELEASE_CANCELLED`;
- `RELEASE_DATE_UNCERTAIN`;
- `RELEASE_REVIEW_REQUIRED`.

### 7.3 Application and boundary errors

| Code | Category | Meaning | State guarantee or fallback |
|---|---|---|---|
| `AUTHENTICATION_REQUIRED` | `authentication` | A protected operation has no valid principal. | No command is executed. |
| `AUTHENTICATION_FAILED` | `authentication` | The identity flow failed. | No rating is created or changed. |
| `AUTHENTICATION_CANCELLED` | `authentication` | The user cancelled sign-in or registration. | Return safely without changing ratings. |
| `RETURN_CONTEXT_INVALID` | `validation` | The post-authentication context is malformed or tampered with. | Ignore it and use a safe destination. |
| `RETURN_CONTEXT_EXPIRED` | `validation` | The rating return context exceeded its lifetime. | Require a new explicit confirmation. |
| `RETURN_CONTEXT_REPLAYED` | `conflict` | The post-authentication context was already consumed. | Do not execute the rating command again; return current state. |
| `SEARCH_QUERY_INVALID` | `validation` | The supplied query violates the search contract. | No search is executed. |
| `FILTER_INVALID` | `validation` | A filter combination or value is invalid. | Return accepted filter values. |
| `PLATFORM_NOT_SUPPORTED` | `validation` | The platform is not part of the supported normalized taxonomy. | Do not silently map it to another platform. |
| `REGION_NOT_SUPPORTED` | `validation` | The region is not supported by the current contract. | Do not silently default to worldwide. |
| `SORT_INVALID` | `validation` | Sort key or direction is unsupported. | Return the supported sort options. |
| `RATING_ALREADY_EXISTS` | `conflict` | Create was requested but an active rating already exists. | Return or point to the existing rating; do not create a duplicate. |
| `RATING_WRITE_CONFLICT` | `conflict` | The rating changed between read and write. | Preserve the winning state and require refresh before retry. |
| `CATALOGUE_NOT_READY` | `technical` | No valid local catalogue snapshot has been published yet. | Do not call IGDB from the user request; expose an operationally diagnosable unavailable state. |
| `CATALOGUE_READ_FAILED` | `technical` | Local catalogue data could not be read. | Do not call IGDB from the user request as an emergency fallback. |
| `RATING_STATISTICS_READ_FAILED` | `technical` | Aggregate context could not be read consistently. | Keep the coherent game response available with an explicit unavailable aggregate; never fabricate a mean or count. |
| `PERSONAL_RATINGS_READ_FAILED` | `technical` | The authenticated user's rating list could not be read. | Do not return partial data from another scope. |
| `RATING_WRITE_FAILED` | `technical` | A create, update, or delete could not be committed. | Preserve the previous valid personal and aggregate state. |
| `PROVIDER_AUTHENTICATION_FAILED` | `dependency` | Backend authentication to IGDB failed. | Keep serving the last valid local data. |
| `PROVIDER_RATE_LIMITED` | `dependency` | IGDB rejected or delayed work due to rate limits. | Apply bounded retry; keep local data. |
| `PROVIDER_UNAVAILABLE` | `dependency` | IGDB timed out or was unavailable. | Keep local data and mark the sync attempt failed. |
| `PROVIDER_RESPONSE_INVALID` | `dependency` | The provider response cannot be interpreted safely. | Reject the affected candidate; keep prior valid data. |
| `PROVIDER_MAPPING_FAILED` | `dependency` | Normalization failed for a provider record. | Isolate the record and continue only when safe. |
| `EXTERNAL_REFERENCE_CONFLICT` | `business_rule` | A provider reference maps inconsistently to internal concepts. | Never merge or create a game silently. |
| `RELEASE_DATA_INVALID` | `business_rule` | A release tuple or date/precision combination is invalid. | Reject the new candidate and preserve prior valid data. |
| `COVER_REFERENCE_INVALID` | `business_rule` | Cover metadata violates usage, host, identifier, or attribution rules. | Use the approved product-owned fallback. |
| `SYNCHRONIZATION_WRITE_FAILED` | `technical` | A valid normalized synchronization result could not be stored. | Preserve the previous local snapshot. |

### 7.4 Important non-error states

The following outcomes must be represented explicitly but must not be reported as
unexpected failures:

| State | Required behaviour |
|---|---|
| No release results | Show an empty state with active filters and a clear reset action. |
| Search returns no games | Explain the bounded-catalogue scope without inventing a match. |
| Search text matches multiple games | Return every matching internal game as a distinct result. |
| No personal ratings | Show the empty `Mis puntuaciones` state. |
| No aggregate ratings | Return count zero, zeroed distribution, and no numeric mean. |
| Aggregate statistics unavailable | Keep the coherent game page available with an explicitly unavailable aggregate. |
| Game not yet rateable | Disable the selector and expose the eligibility reason. |
| Release date is imprecise | Display only the known month, quarter, year, or unknown state. |
| Release data is stale | Show freshness state while continuing to use the last valid local data. |
| Release requires review | Expose the review state and exclude that release from eligibility proof. |
| Provider is unavailable | User reads continue from local data; synchronization records the failure. |
| Provider cover fails | Use the product-owned fallback and keep the game visible. |
| Import candidate requires curation | Keep the candidate outside the domain and do not expand the bounded catalogue. |
| Provider cover requires review | Keep the provider reference undisplayed and use the product-owned fallback. |
| Authentication is cancelled | Return safely to the game without creating a rating. |

## 8. Consistency and transaction boundaries

The implementation technology is not selected here, but the application must provide
these observable guarantees:

- Creating, updating, or deleting a rating changes personal and aggregate contexts as
  one coherent product operation.
- A failed rating command leaves both contexts at their previous valid state.
- `Mis puntuaciones` and the game page converge immediately after a successful
  operation from the user's perspective.
- Duplicate active ratings for the same `UserId + GameId` are prevented even under
  concurrent requests.
- Authentication callback retries or replays execute one logical rating confirmation
  at most once.
- Catalogue synchronization replaces only records that have been normalized and
  validated successfully for existing curated catalogue members.
- Import candidates remain outside the domain until explicit curation succeeds.
- Synchronization never grants provider-cover approval.
- A partial synchronization cannot erase the last valid catalogue snapshot.
- Cover-reference failure never makes a supported game disappear.

Physical transactions, locking, optimistic concurrency, event publication, and
aggregate materialization are implementation decisions to be recorded later.

## 9. Authorization matrix

| Operation | Visitor | Authenticated user | Additional rule |
|---|---:|---:|---|
| Browse releases | Yes | Yes | Catalogue-visible data only. |
| Search catalogue | Yes | Yes | Bounded catalogue only. |
| View game page | Yes | Yes | Personal rating included only for current user. |
| Create rating | No | Yes | Game must be eligible. |
| Update rating | No | Yes | Current user must own the rating; game must remain eligible. |
| Delete rating | No | Yes | Current user must own the rating; eligibility is irrelevant. |
| View `Mis puntuaciones` | No | Yes | Scope before search, sort, or pagination. |
| Synchronize catalogue | No | No | Trusted operator or scheduler boundary only. |

## 10. Observability requirements

Each use-case execution should record enough structured information to diagnose the
journey without exposing sensitive data.

Minimum common fields:

- use-case ID;
- outcome: success, rejected, failed, empty, or degraded;
- stable error code when applicable;
- correlation or trace ID;
- duration;
- internal `gameId` when relevant;
- authenticated-user pseudonymous identifier or one-way correlation value when
  needed, never credentials or raw identity tokens.

Additional synchronization fields:

- provider;
- synchronization run ID;
- requested and processed record counts;
- accepted, review-required, rejected, and unchanged counts;
- staged import-candidate and cover-review-required counts;
- request count, retries, rate-limit responses, and latency;
- previous and new watermark;
- final snapshot freshness;
- run outcome and error summary.

Journey analytics and operational telemetry must remain distinguishable. Product
analytics must not silently become an authorization or domain input.

## 11. Acceptance test catalogue

At minimum, automated acceptance tests should cover:

1. Platform and region filters can be applied and cleared.
2. Search matches canonical and curated Spanish alternative titles.
3. Zero search results remain distinct from technical failure.
4. Search text matching multiple games returns each internal game without arbitrary
   alias resolution.
5. A game page uses internal identity and preserves release date precision.
6. An unauthenticated visitor can browse and read without signing in.
7. Confirming a rating starts authentication and resumes the same game context.
8. Callback retry, reload, or replay executes the logical rating confirmation at
   most once.
9. Evaluation dates come from the trusted application clock in `Europe/Madrid` and
   cannot be supplied or overridden by a client.
10. Values below 1, above 10, or non-integers are rejected.
11. An unreleased, delayed, cancelled, uncertain, or review-required game cannot be
   rated unless another release independently proves eligibility.
12. Day, month, quarter, year, and unknown dates follow the approved eligibility
   thresholds.
13. Creating a rating produces exactly one active rating.
14. A second create cannot produce a duplicate.
15. Updating changes the existing rating and aggregate result.
16. An invalid or failed update preserves the previous rating and aggregate.
17. Deleting removes the rating from game page, aggregate, and `Mis puntuaciones`.
18. Owner deletion remains possible if later release data becomes stale or ineligible.
19. One user cannot read, update, or delete another user's rating, and scoped absence
    returns `RATING_NOT_FOUND` without revealing another user's state.
20. `Mis puntuaciones` scopes before search, sort, and pagination.
21. Mean, count, distribution, one-decimal `half up`, zero-count state, and personal
   versus aggregate labels are correct.
22. Aggregate read failure preserves the game page with an unavailable aggregate.
23. First startup without a valid snapshot returns `CATALOGUE_NOT_READY` and does not
    call IGDB from the user request.
24. Provider failure leaves user reads available from the last valid local data.
25. Invalid provider mappings and external-reference conflicts never silently merge
   games or release tuples.
26. Availability dates never overwrite commercial release dates.
27. A failed or unapproved provider cover uses the product-owned fallback.
28. New or materially changed provider covers remain pending review and are not
    approved by synchronization.
29. New provider results remain outside the domain until explicit curation succeeds.
30. Provider image credentials or binaries never enter the public contract.
31. Synchronization failure preserves the previous valid snapshot.

## 12. Decisions deferred to later contracts

The following details are intentionally not fixed here:

- HTTP methods, paths, status codes, and payload schemas;
- pagination representation and default page size;
- search tokenization, accent handling, stemming, and fuzzy matching;
- identity provider and protocol;
- single-use return-context and command-idempotency implementation; observable
  at-most-once execution for one logical post-authentication confirmation is fixed;
- concurrency-control implementation;
- physical or logical rating deletion;
- aggregate storage or calculation strategy;
- synchronization cadence and stale threshold;
- retry and backoff values;
- database, framework, queue, cache, or deployment model;
- exact Spanish user-facing copy.

## 13. Traceability

| MVP capability | Use cases |
|---|---|
| Recent and upcoming releases | `UC-001` |
| Platform and region filters | `UC-001` |
| Title and alternative-title search | `UC-002`, `UC-008` |
| Game page and release context | `UC-003` |
| Authentication at rating boundary | `UC-004` |
| Create, change, and remove rating | `UC-005`, `UC-006`, `UC-007` |
| Aggregate and personal context | `UC-003`, `UC-005`, `UC-006`, `UC-007` |
| `Mis puntuaciones` | `UC-008` |
| Provider-independent local catalogue | `UC-009` and all read use cases |
| Safe provider-cover usage and fallback | `UC-001`, `UC-002`, `UC-003`, `UC-008`, `UC-009` |
| Basic journey analytics and operational visibility | all use cases |

## 14. Acceptance checklist

- [x] Every included Product Brief capability maps to at least one use case.
- [x] Every use case identifies actor, inputs, main flow, alternatives, and errors.
- [x] Visitor reads remain public and rating operations remain authenticated.
- [x] Client-supplied user identity is never trusted.
- [x] Rating eligibility uses an application-derived evaluation date and the approved
  policy.
- [x] Post-authentication callback replay cannot repeat a logical rating confirmation.
- [x] Create and update preserve at most one active rating per user and game.
- [x] Failed rating commands preserve the previous valid personal and aggregate state.
- [x] `Mis puntuaciones` scopes by authenticated user before search and sort.
- [x] Zero results and no-data conditions are modelled as valid states.
- [x] Provider failures do not enter user request paths or erase local data.
- [x] Provider-specific identities and taxonomies do not become the public contract.
- [x] Synchronization cannot publish uncurated games or approve provider covers.
- [x] Cover failures use the approved fallback without hiding games.
- [x] Stable errors are distinct from localized user messages and transport status.
- [x] Deferred implementation decisions remain outside this document.
- [x] Acceptance tests cover the primary journey and its blocking failure states.

## 15. Change history

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-07-30 | Owner-approved the minimum application contract; made evaluation time trusted, post-authentication confirmation replay-safe, search outcomes non-erroneous, rating ownership non-disclosing, degraded read states explicit, and catalogue synchronization curation-safe. |
| 0.1 | 2026-07-29 | Initial minimum use-case and error contract derived from the approved Product Brief, story map, domain model, provider evidence, and cover ADR. |
