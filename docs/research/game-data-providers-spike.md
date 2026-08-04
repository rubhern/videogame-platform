# Game-data-provider spike — VideoGame Platform

- **Status:** First authenticated PoC reviewed; conditionally approved for the
  learning MVP
- **Started:** 2026-07-23
- **Last updated:** 2026-07-29
- **Phase:** 0 — Product alignment
- **Decision:** Select one provider for the initial bounded catalogue
- **Providers evaluated:** IGDB and RAWG
- **Outcome:** Use IGDB with explicit limitations; retain RAWG as a fallback
- **Decision owner:** Ruben Hernandez

> This spike is not legal advice. It records the evidence and constraints accepted
> for a private, non-commercial learning project. Attribution, retained data, image
> use, partnership, and monetization requirements must be reviewed again before any
> public or commercial release.

## 1. Executive summary

The approved learning MVP needs one external provider for:

- recent and upcoming releases;
- title search;
- a basic game page;
- platforms, genres, companies, dates, and cover references;
- data provenance and freshness.

The documentary comparison ranked IGDB above RAWG because IGDB provides a richer
catalogue model, release dates by platform and region, incremental synchronization,
localization data, and local caching without a published monthly request cap. RAWG is
easier to integrate, but its published free-plan limits, per-page attribution,
redistribution restriction, and contradictory commercial wording introduce more
uncertainty.

The [first authenticated IGDB PoC](igdb-poc-results.md) produced
`CONDITIONAL_PASS`. Exact-title search reached 98%, platform and region representation
reached 100%, all 60 cases were readable offline, and the controlled run completed
187 successful requests with no HTTP 429 response. Release-date and precision
accuracy reached 83.1% against the frozen 90% threshold, while localized-title
coverage reached 40% against a non-blocking 80% threshold.

The thresholds remain unchanged. Ruben Hernandez accepts the observed limitations
for a small personal learning catalogue because they can be handled through explicit
modelling and manual curation. This is not approval for an exhaustive catalogue or
unattended public publication.

### Recommendation

Use IGDB as the initial technical provider under these constraints:

- keep catalogue coverage deliberately bounded and visible;
- maintain product-owned Spanish search aliases;
- distinguish platform releases from subscription availability;
- preserve date precision, provenance, and review state;
- reconcile recent, upcoming, or ambiguous displayed dates manually;
- synchronize and serve local normalized data instead of calling IGDB per page view;
- load approved covers directly from the IGDB image CDN with visible attribution,
  allowlisted delivery, and a product-owned fallback;
- do not import external ratings or copy provider image binaries in the learning MVP.

RAWG remains a fallback. A second provider PoC is unnecessary unless IGDB becomes
incompatible with the intended scope or its limitations become too costly to manage.

## 2. Product context

Product Brief v0.3 defined, and the approved Product Brief preserves, a Spanish-first
learning MVP with:

- a recent and upcoming release view;
- title and alternative-title search;
- a concise game page;
- one external data provider;
- a provider-independent internal model;
- provenance and synchronization state;
- no third-party scores without explicit permission.

The project is personal, part-time, and learning-only. Ruben Hernandez is the sole
owner and human contributor. The initial release mode is private and non-commercial;
public or monetized use is outside the approved Phase 0 scope.

## 3. Questions

1. Can the provider support the primary MVP journey?
2. Can it represent dates by platform, region, and precision?
3. Does it provide the essential metadata and image references?
4. Can it synchronize incrementally and tolerate provider outages?
5. Can normalized data be stored and served locally?
6. Are authentication, limits, and query semantics manageable from a Java backend?
7. Does it provide useful localization support for a Spanish-first product?
8. Which contractual uncertainties affect private, public, or commercial use?

## 4. Scope and limitations

### Included

- official documentation reviewed on 2026-07-23;
- functional, technical, operational, and contractual comparison;
- a provider-independent integration outline;
- a frozen 60-case control sample;
- confirmed acceptance thresholds;
- one authenticated IGDB execution and deterministic offline validation;
- a product decision proportional to the personal learning scope.

### Excluded

- legal review;
- public or commercial launch approval;
- copied provider images;
- external review-score integration;
- exhaustive catalogue validation;
- a production provider adapter or synchronization service;
- an authenticated RAWG PoC.

The spike is sufficient to select IGDB for the current private learning scope. A
change to public distribution, monetization, copied images, or broad catalogue
coverage must reopen the provider release gate.

## 5. Evaluation criteria

| Criterion | Weight | What is evaluated |
|---|---:|---|
| MVP functional coverage | 25% | Games, search, game pages, cover references, platforms, genres, and companies |
| Releases and freshness | 15% | Platform/region dates, date changes, precision, and incremental synchronization |
| Integration simplicity | 10% | Authentication, protocol, documentation, and Java ergonomics |
| Contractual clarity | 20% | Permitted use, attribution, storage, images, monetization, and price |
| Spanish/localization fit | 10% | Localized titles, regions, and supported languages |
| Scalability and operations | 10% | Limits, pagination, caching, webhooks, bulk access, and frontend independence |
| MVP cost | 10% | Initial cost and risk of plan escalation |

## 6. IGDB

### 6.1 Description

IGDB is a video-game database operated within the Twitch ecosystem. Its v4 API
exposes a broad entity model and uses APICalypse queries sent through POST requests.

### 6.2 MVP fit

| Need | Observed support | Assessment |
|---|---|---|
| Game search | Search and filters over `games` | High |
| Weekly releases | `release_dates` with game, platform, region, date, precision, and status | Very high |
| Upcoming releases | Date and status filtering | Very high |
| Basic game page | Name, summary, cover reference, platforms, genres, companies, and websites | Very high |
| Images | Covers, artworks, and screenshots through `image_id` | High, subject to terms |
| Relationships | DLC, expansions, remakes, remasters, collections, and franchises | Very high |
| Supported languages | `language_supports` and support types | High |
| Localized titles and covers | `game_localizations` by region | Medium-high |
| External ratings | User and aggregate rating fields | Available but excluded |
| Incremental synchronization | `updated_at`, filtered queries, and webhooks | Very high |

### 6.3 Authentication and integration

Requirements:

1. Twitch account with two-factor authentication.
2. A confidential Twitch application.
3. `Client ID` and `Client Secret`.
4. OAuth 2.0 token through `client_credentials`.
5. `Client-ID` and `Authorization: Bearer ...` request headers.

Relevant technical behavior:

- direct browser calls are not supported and would expose credentials;
- the published limit is four requests per second and eight concurrent requests;
- one request returns at most 500 elements;
- multi-query can group requests;
- tokens expire and must be renewed;
- IGDB recommends storing and serving data locally.

The PoC uses a sequential three-request-per-second limit and keeps provider calls
behind a backend boundary.

### 6.4 Example release query

```http
POST https://api.igdb.com/v4/release_dates
Client-ID: ${IGDB_CLIENT_ID}
Authorization: Bearer ${IGDB_ACCESS_TOKEN}
Accept: application/json

fields date,human,date_format,
       game.id,game.name,game.slug,game.cover.image_id,
       platform.id,platform.name,
       release_region.region,status.name,updated_at;
where date >= ${FROM_EPOCH}
  & date < ${TO_EPOCH};
sort date asc;
limit 500;
```

The authenticated PoC confirmed the relevant query and response behavior. Production
queries must continue to use current, non-deprecated fields.

### 6.5 Proposed synchronization

- Import only the bounded catalogue and relevant release window.
- Use `updated_at` as the initial incremental watermark.
- Reconcile the upcoming window regularly because dates change.
- Keep webhooks as a later optimization.
- Persist normalized data and do not fan out to IGDB on page views.
- Persist cover `image_id` references and source metadata, not provider image
  binaries.
- Recheck referenced covers when their provider metadata changes and fall back
  safely when a reference no longer resolves.
- Apply a local limit of three requests per second.

### 6.6 Terms and release-mode boundary

The official documentation describes free non-commercial use under the Twitch
Developer Service Agreement and asks commercial products to contact IGDB. IGDB also
documents local data caching, image URLs constructed from `image_id`, image-size
variants, and visible attribution expectations. The Twitch agreement additionally
requires care with stored copies, redistribution, updates, attribution, and the path
back to source material.

For Phase 0 the owner accepts only:

- private, non-commercial learning use;
- local normalized metadata;
- direct delivery of approved covers from the documented IGDB image CDN;
- storage of image references and normalized metadata, not provider image binaries;
- visible IGDB attribution and a clear path to the matching source game;
- a fixed HTTPS host and image-size allowlist plus a product-owned fallback;
- no committed raw responses or credentials;
- no copied, proxied, persisted, committed, or redistributed provider image binaries;
- no external ratings.

This reference-only mode is recorded in
[ADR-0001](../decisions/0001-reference-igdb-cover-images.md). Before public,
monetized, copied-image, application-storage, or redistributed use, confirm the
applicable partnership, attribution, retained-data, image, and rating requirements.
This future check does not block the current private learning scope.

### 6.7 Spanish and localization

IGDB provides localized names/covers and information about languages supported by a
game. It does not guarantee Spanish `summary` or `storyline` content.

Therefore:

- keep `sourceSummary` and `sourceSummaryLanguage` explicit;
- maintain `editorialSummaryEs` as product-owned content;
- never present an automatic translation as official provider content;
- maintain product-owned Spanish aliases for search when provider coverage is absent.

### 6.8 Strengths

- Rich, normalized model.
- Strong platform/region release representation.
- Incremental synchronization support.
- Local caching aligns with a provider-independent domain model.
- No published monthly cap.
- Useful localization and language entities.

### 6.9 Limitations

- Twitch OAuth and APICalypse add integration complexity.
- The four-request-per-second limit requires controlled synchronization.
- Schema changes and deprecated fields require contract tests.
- Public/commercial, copied-image, application-storage, and redistribution
  requirements must be reviewed before that release mode.
- Spanish editorial content remains a product responsibility.
- The first PoC found release-date and localized-title limitations.

## 7. RAWG

### 7.1 Description

RAWG provides a REST catalogue API authenticated with an API key. It advertises a
large catalogue with platforms, images, stores, ratings, developers, and publishers.

### 7.2 MVP fit

| Need | Observed support | Assessment |
|---|---|---|
| Game search | `GET /api/games?search=...` | High |
| Weekly/upcoming releases | `dates` and `platforms` filters | High |
| Basic game page | Descriptions, genres, dates, stores, websites, and requirements | High |
| Images | Backgrounds and screenshots | High, subject to terms |
| Relationships | Parent games, DLC, and series | Medium-high |
| Supported languages | No equivalent published strength | Low-medium |
| Localized titles/covers | No documented completeness guarantee | Low |
| External ratings | RAWG ratings and Metacritic data | Functionally high, contractually pending |
| Incremental synchronization | Updated timestamp and filters | Medium |

### 7.3 Integration

- API key in the query string.
- Conventional GET-based REST API.
- Filters for dates, platforms, developers, genres, tags, and Metacritic.
- Standard pagination.

```http
GET https://api.rawg.io/api/games
    ?key=${RAWG_API_KEY}
    &dates=2026-07-20,2026-07-26
    &ordering=released
    &page_size=40
```

### 7.4 Published limits and terms

The pricing page reviewed on 2026-07-23 described:

- **Free:** personal, hobby, non-commercial projects; 20,000 requests per month;
  backlinks required.
- **Business:** USD 149/month; commercial use; 50,000 requests per month.
- **Enterprise:** up to 1,000,000 requests per month with custom terms.

The same page also contained older wording suggesting some free commercial use. That
contradiction must be clarified before selecting RAWG for any commercial mode.

RAWG requires attribution and an active link on pages using its data or images and
prohibits redistribution or resale. These rules affect product design and public API
boundaries.

### 7.5 Images and external scores

RAWG does not claim ownership of every supplied image. Exposure through the API does
not prove a complete rights chain for reuse.

Likewise, exposure of Metacritic data does not by itself grant VideoGame Platform the
right to store or display it. External scores remain outside the MVP.

### 7.6 Spanish and localization

RAWG does not document comprehensive Spanish descriptions, regionalized titles, or
supported-language classification as a primary capability. A product-owned Spanish
editorial and alias layer would still be necessary.

### 7.7 Strengths

- Simple REST integration.
- Direct date and platform filters.
- Broad catalogue and image references.
- Stores, requirements, videos, and ratings may be useful later.

### 7.8 Limitations

- Monthly cap on the published free plan.
- Material price increase for commercial use.
- Contradictory commercial wording.
- Per-page attribution and backlink requirement.
- Redistribution restriction.
- Unclear image rights chain.
- Weaker documented localization support.

## 8. Weighted comparison

Scores use a 1–5 scale. They are the spike's technical assessment, not provider
claims.

| Criterion | Weight | IGDB | RAWG | Rationale |
|---|---:|---:|---:|---|
| MVP functional coverage | 25% | 5.0 | 4.5 | Both cover the journey; IGDB models more relationships |
| Releases and freshness | 15% | 5.0 | 4.0 | IGDB exposes platform/region dates, `updated_at`, and webhooks |
| Integration simplicity | 10% | 3.0 | 5.0 | RAWG uses REST and an API key |
| Contractual clarity | 20% | 3.0 | 2.0 | Both need care; RAWG publishes contradictory wording |
| Spanish/localization fit | 10% | 3.0 | 2.0 | IGDB provides localization and language entities |
| Scalability and operations | 10% | 4.5 | 3.0 | IGDB favors local sync without a monthly cap |
| MVP cost | 10% | 4.5 | 4.0 | Both permit non-commercial evaluation; RAWG has an explicit commercial step |
| **Weighted result** | **100%** | **82/100** | **69.5/100** | IGDB is the preferred initial provider |

## 9. Provider-independent product model

The external provider must not define the internal model. A provider adapter belongs
inside the initial modular monolith; the MVP does not need a provider microservice.

```text
Frontend
   |
VideoGame Platform API
   |
Application use cases
   |
GameCatalogProvider port
   |
IGDB adapter
   |
IGDB API
```

### 9.1 Minimal provider port

```java
public interface GameCatalogProvider {

    List<ProviderRelease> findReleases(
        LocalDate from,
        LocalDate to,
        Set<PlatformRef> platforms
    );

    List<ProviderGameSummary> searchGames(String query, int limit);

    Optional<ProviderGameDetails> getGame(ProviderGameId providerId);

    List<ProviderChange> findChanges(Instant updatedAfter, int limit);
}
```

The port expresses product needs, not provider endpoints. Subscription availability
is not inferred from `findReleases`; it enters through a separate source or curated
workflow when the product chooses to support it.

### 9.2 Minimal canonical concepts

The approved canonical contract is maintained in the
[learning MVP domain model](../architecture/domain/mvp-domain-model.md). The provider
adapter supplies only the input needed to construct those concepts:

```text
Game
- internalId
- canonicalTitle
- slug
- editorialSummaryEs
- sourceSummary
- sourceSummaryLanguage
- coverReference
- releases[]
- externalReferences[]
- provenance

Release
- releaseId
- platform
- region
- releaseDate
- status
- provenance
- providerUpdatedAt
- lastSyncedAt
- lastVerifiedAt
- verificationLevel
- reviewStatus
- freshnessStatus
- externalReferences[]

CoverReference
- imageId
- source
- usageMode
- alternativeText
- usageStatus
- providerUpdatedAt
- lastCheckedAt

ExternalReference
- provider
- entityType
- providerId
- providerUrl
```

`Release` means the first or subsequent commercial release of a game for one
platform and region. `Availability` means access through a subscription, rotating
catalogue, promotion, or similar service. A Game Pass arrival must never overwrite or
be compared as an original platform release. `Availability` remains a protected
definition outside the MVP API and persistence model.

`ReleaseDate` is a valid exact-day, month, quarter, year, or unknown variant.
Verification level, review need, and freshness remain independent dimensions.
`CoverReference` uses `provider_cdn_reference` for approved IGDB covers and
`product_owned` for the fallback.

### 9.3 Modelling and validation rules

- Generate provider-independent internal identifiers.
- Keep external references separate from game identity.
- Validate platform, region, date, precision, and status as one release tuple.
- Never combine fields from different provider release records.
- Never map subscription availability into `Release`.
- Preserve provider values and explicit `unknown` states.
- Keep provenance and freshness on every release or availability.
- Require manual verification for displayed recent/upcoming dates when sources
  disagree or the provider value is stale.
- Keep Spanish aliases and editorial content product-owned.
- Keep provider images as allowlisted, attributed CDN references; never normalize the
  binary into product storage.
- Persist `providerUpdatedAt`, `lastSyncedAt`, `syncStatus`, and synchronization
  errors.
- Serve the last synchronized data during provider failure.
- Use fixture-based contract tests for normalization.

## 10. Authenticated PoC

### 10.1 Execution order

1. Freeze the sample and thresholds.
2. Obtain IGDB development credentials.
3. Run local tests and an authenticated capture.
4. Validate captured canonical data offline.
5. Review every `REVIEW` and material failure.
6. Record the owner decision and accepted limitations.
7. Evaluate RAWG only if IGDB later becomes unsuitable.

### 10.2 Frozen control sample

The PoC uses the 60 cases in
[`igdb-poc-sample.csv`](igdb-poc-sample.csv), frozen on 2026-07-24 before the
authenticated execution:

- 10 recent releases;
- 10 upcoming releases;
- 10 Spanish or regionalized titles;
- 10 lesser-known indie games;
- 10 legacy games with several platforms or versions;
- 5 DLC or expansions;
- 5 delayed or imprecisely dated games.

The `expected_*` columns represent expectations from linked official evidence, not
IGDB observations. Blank values make no assertion. Date precision remains distinct
from the date value.

### 10.3 Confirmed acceptance thresholds

- **Status:** Frozen before execution
- **Decision owner:** Ruben Hernandez
- **Decision date:** 2026-07-24
- **Sample:** [`igdb-poc-sample.csv`](igdb-poc-sample.csv)
- **Decision vocabulary:** `PASS`, `CONDITIONAL_PASS`, `REVIEW`, `FAIL`

#### Data gate

| Metric | Threshold | Classification |
|---|---:|---|
| Exact-title search | ≥ 95% | Blocking |
| Alternative/localized-title search | ≥ 80% | Accepted limitation |
| Provider ID, provenance, and synchronization timestamp | 100% | Blocking |
| Platform correctly identified | ≥ 95% | Blocking |
| Release date or precision correctly represented | ≥ 90% | Blocking |
| Region correct or explicitly unknown | ≥ 85% | Blocking |
| Usable cover reference | ≥ 90% | Non-blocking |
| Genre identifiable | ≥ 90% | Non-blocking |
| Developer or publisher identifiable | ≥ 85% | Non-blocking |
| Cancelled/delayed games shown as normal releases | 0 | Blocking |
| DLC, expansion, port, or remaster silently merged | 0 | Blocking |
| Unexpected duplicates in normal results | ≤ 5% | Blocking |

Spanish summaries are not a gate because Spanish editorial content is product-owned.

#### Technical and operational gate

| Metric | Threshold |
|---|---:|
| Secrets in Git, frontend, results, or logs | 0 |
| Browser calls directly to IGDB | 0 |
| Configured request rate | ≤ 3 requests/second |
| HTTP 429 responses under controlled load | 0 |
| Successful requests after bounded retry | ≥ 99% |
| Synchronized cases readable without IGDB | 100% |
| Silent normalization errors | 0 |
| Request count, latency, and errors recorded | Every run |

Local p95 latency is observational and is not an SLA.

### 10.4 First authenticated result

The reviewed result is documented in
[`igdb-poc-results.md`](igdb-poc-results.md).

| Result | Evidence |
|---|---:|
| Generated decision | `CONDITIONAL_PASS` |
| Cases | 41 `PASS`, 9 `REVIEW`, 10 `FAIL` |
| Exact-title search | 98.0% |
| Platform | 100% |
| Region | 100% |
| Release date and precision | 83.1%, plus 2 reviews |
| Alternative/localized title | 40% |
| Metadata, provenance, and timestamp | 100% |
| Offline readability | 60/60 |
| Requests | 187; 100% successful; 0 HTTP 429; p95 561 ms |

Release accuracy did not strictly meet the blocking 90% threshold. Continuing is an
explicit owner exception for this bounded learning experiment, not a claim that the
threshold passed.

The main causes were:

- Game Pass availability used as if it were a platform release;
- one-day timezone or source-normalization differences;
- a broad `PC` label that did not distinguish DOS from modern Windows;
- provider dates more precise than the frozen sample;
- one genuinely missing title.

These findings produced the separate `Release` and `Availability` concepts in
section 9.

## 11. Risks and mitigations

| Risk | Probability | Impact | Initial mitigation |
|---|---|---|---|
| Public/commercial terms not closed | High if scope changes | High | Keep Phase 0 private and non-commercial; reopen before public release |
| Image rights and storage boundaries | Medium-high | High | Use attributed direct IGDB CDN references under ADR-0001; do not copy or proxy provider binaries |
| Incomplete Spanish content | High | Medium-high | Product-owned Spanish aliases and editorial content |
| Versions and editions duplicated | High | Medium | Canonicalization and explicit type/parent review |
| Future dates change | High | Medium | Regular reconciliation and manual review state |
| Provider outage or throttling | Medium | Medium | Local persistence, bounded retry, rate limit, and stale data |
| Provider schema changes | Medium | Medium | Defensive mapping and contract tests |
| External-rating coupling | Medium | High | Keep external ratings outside the MVP |
| Solo-project operational load | High | Medium | Bounded catalogue and manual workflows before automation |

## 12. Decision after the PoC

### Approved for the current scope

- **Provider:** IGDB.
- **Owner:** Ruben Hernandez.
- **Decision date:** 2026-07-24.
- **Referenced-cover decision date:** 2026-07-29.
- **Release mode:** private, non-commercial learning.
- **Use:** bounded catalogue, search, game page, releases, local normalization, and
  direct IGDB CDN cover references under ADR-0001.
- **Decision type:** reversible if provider constraints or product scope change.

### Accepted limitations

- Declared catalogue boundary.
- Product-owned Spanish aliases.
- Manual reconciliation for selected recent/upcoming dates.
- Separate release and subscription-availability concepts.
- Imprecise and unknown dates/statuses remain explicit.
- Local synchronized reads rather than provider calls per page.
- Provider-hosted covers require attribution, a clear source path, an allowlisted
  image host, and a product-owned fallback.
- One provider only.

### Explicitly excluded

- Public or monetized release.
- Copied, proxied, persisted, committed, or redistributed provider image binaries.
- External ratings or professional-review aggregation.
- Exhaustive catalogue coverage.
- RAWG integration.
- A provider microservice.

### Conditions that reopen the decision

- public deployment;
- monetization;
- copied, application-stored, or redistributed images/data;
- broad unattended catalogue synchronization;
- accepted limitations becoming too expensive;
- material IGDB contract or API changes.

## 13. Spike answers

| Question | Answer |
|---|---|
| Are providers viable? | Yes. IGDB is viable for the bounded private learning scope; RAWG remains a documentary fallback |
| Which provider should be used first? | IGDB |
| Is the current technical decision sufficient? | Yes for private learning with referenced CDN covers; no for public, copied-image, application-storage, redistribution, or commercial use |
| Which provider is simpler technically? | RAWG |
| Which provider has the stronger catalogue/release model? | IGDB |
| Which provider shows greater contractual ambiguity? | RAWG |
| Do they solve Spanish editorial content? | No; that remains product-owned |
| Should the frontend call them in real time? | No; synchronize and serve local data |
| Should external ratings enter the MVP? | No |

## 14. Completed and deferred actions

### Completed

- [x] Compare IGDB and RAWG.
- [x] Freeze the 60-case sample and acceptance thresholds.
- [x] Build an isolated Java CLI and fixture-based tests.
- [x] Configure authenticated local execution without committed secrets.
- [x] Execute and review the first real PoC.
- [x] Measure coverage, duplicates, latency, request behavior, and date quality.
- [x] Record the accepted limitations and owner decision.
- [x] Separate release from subscription availability in the canonical model.
- [x] Approve the provider-independent domain model.
- [x] Record referenced IGDB cover delivery in ADR-0001.
- [x] Update Product Brief, assumptions, open questions, and glossary.

### Deferred until the release mode changes

- [ ] Confirm public/commercial partnership and attribution requirements.
- [ ] Confirm copied-image, application-storage, redistribution, and retained-data
  requirements.
- [ ] Evaluate RAWG only if IGDB becomes unsuitable.

### Small follow-up outside Product Brief closure

- [ ] Add one applicable cancelled or delayed release regression case.
- [ ] Correct future sample rows that use subscription availability as release date.
- [ ] Capture the Git commit in any later authenticated report.

## 15. Sources

Provider comparison reviewed on **2026-07-23**. IGDB image delivery, caching,
attribution, and developer-agreement material refreshed on **2026-07-29**.

### IGDB

- IGDB API documentation: <https://api-docs.igdb.com/>
- IGDB API overview: <https://www.igdb.com/api>
- Twitch Developer Service Agreement:
  <https://www.twitch.tv/p/en/legal/developer-agreement/>

Verified topics:

- Twitch OAuth authentication;
- four requests per second and eight open requests;
- 500-item response limit;
- game, release, image, localization, and language entities;
- multi-query, webhooks, and `updated_at`;
- documented local caching;
- partnership and attribution expectations.

### RAWG

- RAWG API overview, pricing, and terms: <https://rawg.io/apidocs>
- RAWG interactive API documentation: <https://api.rawg.io/docs/>

Verified topics:

- API-key authentication;
- date and platform filters;
- published 20,000-request free plan;
- published Business price and request limit;
- attribution and backlinks;
- redistribution restriction;
- contradictory commercial wording;
- advertised catalogue, images, stores, ratings, and Metacritic data.

## 16. Confidence

- **Functional fit:** high for the bounded learning MVP.
- **Technical integration:** high for the evaluated PoC behavior.
- **Identity, platform, region, and metadata quality:** medium-high.
- **Release-date quality:** medium and manageable with the accepted workflow.
- **Spanish alias coverage:** low-medium and explicitly product-owned.
- **Private non-commercial scope:** medium-high based on published documentation.
- **Public/commercial terms:** low-medium until the release mode requires review.
