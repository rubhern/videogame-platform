# Backend code review — 2026-08-21

- **Status:** Triaged
- **Review source:** AI-assisted backend review performed with Claude
- **Review date:** 2026-08-21
- **Triage date:** 2026-08-23
- **Scope:** `backend/`, backend/root Maven configuration, runtime configuration, migrations and backend tests
- **Analysed commit:** Unknown — the source review was provided without a Git SHA
- **Authority:** Review evidence and engineering input; approved product, architecture, API, platform and delivery records remain authoritative
- **Follow-up:** GitHub issues #79–#86

> The original review was not tied to an immutable source revision. File names and
> line references recorded below identify the code Claude inspected at review time,
> but they must be treated as orientation rather than durable evidence. Every finding
> must be revalidated against the current branch before implementation.

## 1. Purpose

This document preserves and triages the backend review performed on 2026-08-21.

It is **not** a second backlog and it is **not** an instruction to implement every
recommendation in the source review. Its purpose is to:

- preserve useful technical findings;
- distinguish defects and concrete risks from refactoring ideas and speculative optimizations;
- reconcile recommendations with the already approved architecture and API decisions;
- record which findings are accepted, modified, deferred or rejected;
- link accepted follow-up work to focused GitHub issues;
- avoid applying AI-review advice without first confirming that the current code still exhibits the reported problem.

Authoritative project records include:

- [MVP solution architecture](../../architecture/mvp-solution-architecture.md)
- [MVP API conventions](../../architecture/api/api-conventions.md)
- [MVP technology baseline](../../architecture/technology/mvp-technology-baseline.md)
- [MVP platform and delivery design](../../architecture/deployment/mvp-platform-and-delivery.md)
- [Delivery lifecycle](../delivery-lifecycle.md)

## 2. Executive assessment

The source review assessed the backend positively overall. It highlighted that the implementation already had a solid base:

- real hexagonal boundaries and ports;
- Spring Modulith dependency declarations;
- ArchUnit fitness functions;
- OpenAPI as the contract source of truth;
- PostgreSQL-side filtering, ordering, counting and pagination;
- ETag and `Cache-Control` support;
- stable Problem Details handling;
- Flyway with separated migration/runtime roles;
- Hibernate schema generation disabled.

That assessment is consistent with the intent of the approved MVP architecture.

The most valuable findings are concentrated in four areas:

1. **Failure correctness and diagnosability** — exception logging, typed problem codes, correlation consistency and correct distinction between transient unavailability and invalid persisted data.
2. **Application and boundary type safety** — application invariants should not depend on HTTP validation, and compile-time exhaustive handling should replace runtime `name()`/cast conventions where possible.
3. **Persistence resilience** — query execution needs explicit bounds and SQL composition/configuration should be less fragile.
4. **Test and contract evidence** — integration tests should be independent and the implementation should prove HTTP/OpenAPI behaviour rather than only generate interfaces from the contract.

Several performance recommendations are plausible but are deliberately **deferred to measurement**. The approved architecture requires measuring before optimizing, and the approved API currently uses one-based page pagination. Cursor pagination, origin-side caching and virtual threads are therefore experiments, not mandatory technical debt.

## 3. Triage vocabulary

| Decision | Meaning |
|---|---|
| `ACCEPT` | The finding describes a concrete defect, risk or maintainability problem worth correcting. |
| `ACCEPT WITH MODIFICATION` | The underlying concern is valid, but the proposed solution conflicts with or overshoots an approved project decision. |
| `DEFER / EXPERIMENT` | Plausible improvement that requires measurement, later scope or an explicit trigger before adoption. |
| `REJECT AS PROPOSED` | The recommendation is not justified for the current baseline, conflicts with an approved decision, or duplicates an existing control. |
| `REVALIDATE` | All findings implicitly require this because the analysed commit is unknown. |

Priority is relative to the current backend slice, not a delivery commitment.

## 4. Detailed triage

### 4.1 Bugs and functional risks

| ID | Source finding | Decision | Priority | Follow-up |
|---|---|---|---|---|
| 1.1 | Unexpected `500`/technical failures may be converted to Problem Details without retaining a useful server-side trace. | `ACCEPT` | High | [#79](https://github.com/rubhern/videogame-platform/issues/79) |
| 1.2 | `ProblemCode.fromValue(String)` can itself fail inside `ApiExceptionHandler`. | `ACCEPT` | High | [#79](https://github.com/rubhern/videogame-platform/issues/79) |
| 1.3 | `ApiRequestException` and related telemetry use duplicated string error codes. | `ACCEPT` | High | [#79](https://github.com/rubhern/videogame-platform/issues/79) |
| 1.4 | Problem body `correlationId` may diverge from the `X-Correlation-ID` response header. | `ACCEPT` | High | [#79](https://github.com/rubhern/videogame-platform/issues/79) |
| 1.5 | Sealed variants are handled through `instanceof`/unchecked fallback casts rather than exhaustively. | `ACCEPT` | Medium | [#80](https://github.com/rubhern/videogame-platform/issues/80) |
| 1.6 | Invalid persisted data may be translated into a retryable catalogue `503`. | `ACCEPT` | High | [#79](https://github.com/rubhern/videogame-platform/issues/79) |
| 1.7 | `BrowseReleasesUseCase.Query` relies on HTTP validation for page/view invariants. | `ACCEPT` | High | [#80](https://github.com/rubhern/videogame-platform/issues/80) |
| 1.8 | `If-None-Match` is parsed manually with incomplete RFC semantics. | `ACCEPT` | Medium | [#84](https://github.com/rubhern/videogame-platform/issues/84) |

#### Triage notes

**1.1–1.4 and 1.6 are treated as one error-boundary concern.** Stable client errors and useful diagnostics are both required. Technical causes must not be exposed to the client, but losing them before logging makes production diagnosis unnecessarily hard.

**1.7 is considered an application-design defect rather than only defensive programming.** A public application use-case contract must defend its invariants even when called from a non-HTTP adapter or test.

**1.8 should prefer framework-supported conditional-request semantics** rather than introducing another custom parser.

### 4.2 Design and architecture

| ID | Source finding | Decision | Priority | Follow-up |
|---|---|---|---|---|
| 2.1 | Domain, application and generated API contain parallel enum vocabularies coupled with `name()`/`valueOf()`; one freshness type appears unused. | `ACCEPT` | Medium | [#80](https://github.com/rubhern/videogame-platform/issues/80) |
| 2.2 | `publicationVersion` is transported but not exploited to compute a cheaper conditional-request validator. | `DEFER / EXPERIMENT` | Medium | [#85](https://github.com/rubhern/videogame-platform/issues/85) |
| 2.3 | Strict allowed-query-parameter policy duplicates endpoint contract metadata and depends on exact URI matching. | `ACCEPT` | Medium | [#82](https://github.com/rubhern/videogame-platform/issues/82) |
| 2.4 | `view` is parsed manually even though its wire values are already constrained by OpenAPI. | `ACCEPT` | Medium | [#82](https://github.com/rubhern/videogame-platform/issues/82) |
| 2.5 | IGDB CDN/provider policy and localized fallback presentation text sit in generic API/application code. | `ACCEPT` | Medium | [#82](https://github.com/rubhern/videogame-platform/issues/82) |
| 2.6 | Application `ReleasePage` collides with generated `ReleasePage` and contains many nested types. | `ACCEPT` selectively | Low/Medium | [#80](https://github.com/rubhern/videogame-platform/issues/80) |
| 2.7 | Custom release metrics are intertwined with delivery and may duplicate/contaminate standard HTTP measurements. | `ACCEPT` | Medium | [#83](https://github.com/rubhern/videogame-platform/issues/83) |
| 2.8 | Transaction/JDBC policy is partly constructed inside the adapter; shared mutable template and positional binding increase fragility. | `ACCEPT` | High | [#81](https://github.com/rubhern/videogame-platform/issues/81) |

#### Triage notes

**2.1 does not imply merging distinct concepts only because enum names currently match.** The objective is to eliminate accidental runtime coupling while preserving real domain/application/API boundaries.

**2.2 is a strong optimization candidate**, because an immutable publication version can potentially make a `304` materially cheaper. It still needs correctness and performance evidence before retention.

**2.5 reinforces existing architecture**, which already requires provider-specific concepts to remain behind provider/catalogue boundaries and presentation concerns to remain outside application orchestration.

### 4.3 Performance and scalability

| ID | Source finding | Decision | Priority | Follow-up |
|---|---|---|---|---|
| 3.1 | Release SQL changes semantics through textual `String.replace(...)`. | `ACCEPT` | High | [#81](https://github.com/rubhern/videogame-platform/issues/81) |
| 3.2 | Platform/region taxonomies and filtered counts are queried repeatedly although publication data is immutable. | `DEFER / EXPERIMENT` | Medium | [#85](https://github.com/rubhern/videogame-platform/issues/85) |
| 3.3 | OFFSET plus the current ordering may become expensive on deep pages. | `DEFER / EXPERIMENT` | Later | [#85](https://github.com/rubhern/videogame-platform/issues/85) |
| 3.4 | No origin-side application cache exists for immutable release browse data. | `DEFER / EXPERIMENT` | Later | [#85](https://github.com/rubhern/videogame-platform/issues/85) |
| 3.5 | Enable Java virtual threads for the JDBC workload. | `DEFER / EXPERIMENT` | Later | [#85](https://github.com/rubhern/videogame-platform/issues/85) |

#### Triage notes

The source review is technically reasonable that OFFSET, repeated counts and blocking JDBC may become scalability constraints. They are **not automatically current defects**.

The approved architecture explicitly requires performance baselines and measurement before adding optimization infrastructure. The approved API conventions also state that a future cursor contract requires a separate compatibility decision.

Therefore:

- page-based pagination remains authoritative until evidence justifies reopening it;
- caching must prove invalidation/correctness and measurable benefit;
- virtual threads must be compared with the synchronous baseline rather than enabled as a default toggle;
- no Redis, search engine, new database or distributed cache belongs in this work.

### 4.4 Observability, security and operation

| ID | Source finding | Decision | Priority | Follow-up |
|---|---|---|---|---|
| 4.1 | `/actuator/metrics` is exposed on the application HTTP surface. | `ACCEPT` | Medium | [#83](https://github.com/rubhern/videogame-platform/issues/83) |
| 4.2 | Catalogue readiness checks PostgreSQL and may cascade transient database issues across instances. Source recommendation: remove DB from readiness. | `ACCEPT WITH MODIFICATION` | Medium | [#83](https://github.com/rubhern/videogame-platform/issues/83) |
| 4.3 | `CorrelationIdFilter` ordering, health-probe access logging and correlation/trace integration deserve review. | `ACCEPT` | Medium | [#83](https://github.com/rubhern/videogame-platform/issues/83) |
| 4.4 | Database/query/pool/request execution bounds are not explicit. | `ACCEPT` | High | [#81](https://github.com/rubhern/videogame-platform/issues/81) |
| 4.5 | Complete HTTP security headers/proxy/rate-limit responsibility is not visible in the reviewed slice. | `ACCEPT WITH MODIFICATION` | Medium/Later | [#86](https://github.com/rubhern/videogame-platform/issues/86) |

#### Triage notes

**4.2 is not accepted as proposed.** The approved platform design says readiness verifies the ability to serve supported local-data behaviour and reach required local dependencies. PostgreSQL is a required local dependency for the current releases API.

The follow-up is therefore to make the readiness check:

- cheap;
- bounded by an explicit timeout;
- operationally observable;
- independent from IGDB and telemetry;

not to automatically remove PostgreSQL from readiness.

**4.5 must respect the same-origin BFF design.** Absence of a permissive CORS configuration is not a defect when cross-origin browser access is intentionally not supported. Security headers, trusted proxy semantics and `429` ownership still need explicit evidence before private-dev acceptance.

### 4.5 Dependencies and build

| ID | Source finding | Decision | Priority | Follow-up |
|---|---|---|---|---|
| 5.1 | `spring-boot-starter-data-jpa` is currently unused by entities in the reviewed release implementation and could be replaced with JDBC. | `DEFER / REEVALUATE` | Later | No dedicated issue |
| 5.2 | Generated OpenAPI interfaces exist but implementation responses are not independently checked for conformance. | `ACCEPT` | Medium | [#84](https://github.com/rubhern/videogame-platform/issues/84) |
| 5.3 | Add a JaCoCo percentage gate and OWASP dependency-check. | `REJECT AS PROPOSED` | — | No dedicated issue |

#### Triage notes

**5.1 is not a clean removal under the approved baseline.** The technology baseline selects Spring Data JPA/Hibernate for transactional persistence while explicitly allowing JDBC/explicit SQL for read projections. Removing JPA solely because the first read slice does not use entities may cause churn once rating write aggregates arrive. Revisit once the first write-side persistence is implemented.

**5.3 conflicts with current quality policy as stated.** The baseline describes JaCoCo as coverage evidence rather than a blind global target and already selects SonarCloud, CodeQL, Dependabot, secret scanning, Trivy and CycloneDX for quality and software-supply-chain controls. A new arbitrary percentage threshold or additional dependency scanner should be introduced only if it closes a demonstrated gap.

### 4.6 Test quality

The source review identified these concrete test issues:

| Finding | Decision | Follow-up |
|---|---|---|
| Integration tests mutate shared publication state and/or assert accumulated meter values. | `ACCEPT` | [#84](https://github.com/rubhern/videogame-platform/issues/84) |
| JSON assertions compare serialized `toString()` output/property order. | `ACCEPT` | [#84](https://github.com/rubhern/videogame-platform/issues/84) |
| Database container startup uses a static initializer that may be simplified with supported Spring/Testcontainers integration. | `ACCEPT` if still applicable | [#84](https://github.com/rubhern/videogame-platform/issues/84) |
| Missing HTTP tests for selected `405`, `406`, required `view`, fallback `500` and `Allow` semantics. | `ACCEPT` | [#84](https://github.com/rubhern/videogame-platform/issues/84) |
| OpenAPI declares `429` without clear implementation evidence. | `ACCEPT` as a contract-ownership inconsistency | [#84](https://github.com/rubhern/videogame-platform/issues/84), [#86](https://github.com/rubhern/videogame-platform/issues/86) |
| Scalability test asserts PostgreSQL `EXPLAIN` text and writes diagnostics to `System.out`. | `ACCEPT` selectively | [#84](https://github.com/rubhern/videogame-platform/issues/84) |

The target is not “more tests” generically. The target is deterministic evidence for contract behaviour, failure semantics, isolation and performance properties.

## 5. Follow-up issue register

The accepted work is deliberately grouped by coherent engineering outcome rather than creating one issue per review bullet.

| Issue | Outcome | Suggested order |
|---|---|---:|
| [#79 Harden Release API error semantics and correlation](https://github.com/rubhern/videogame-platform/issues/79) | Diagnosable and contract-safe failure boundary | 1 |
| [#80 Strengthen catalogue application contracts and type safety](https://github.com/rubhern/videogame-platform/issues/80) | Application invariants and compile-time-safe mappings | 2 |
| [#81 Harden catalogue JDBC execution and transaction configuration](https://github.com/rubhern/videogame-platform/issues/81) | Explicit SQL, transaction policy and timeouts | 1 |
| [#82 Simplify the releases API boundary and isolate provider-specific policy](https://github.com/rubhern/videogame-platform/issues/82) | Contract-driven query handling and provider isolation | 3 |
| [#83 Rationalize release observability and management endpoint exposure](https://github.com/rubhern/videogame-platform/issues/83) | Accurate metrics, correlation and safe management/readiness behaviour | 3 |
| [#84 Strengthen release API contract and integration-test reliability](https://github.com/rubhern/videogame-platform/issues/84) | Independent tests and implementation-level contract evidence | 2 |
| [#85 Benchmark and optimize the immutable releases browse path](https://github.com/rubhern/videogame-platform/issues/85) | Evidence-driven ETag/cache/pagination/threading decisions | 5 |
| [#86 Harden the same-origin HTTP security boundary before private dev acceptance](https://github.com/rubhern/videogame-platform/issues/86) | Browser/proxy/header/rate-limit policy consistent with the BFF design | 4 |

Suggested sequencing:

```text
#79 + #81
    ↓
#80 + #84
    ↓
#82 + #83
    ↓
#86 (coordinated with identity/private-dev work)
    ↓
#85 only when a stable performance baseline is useful
```

Parallel execution is possible where diffs do not overlap, but each issue should remain independently reviewable.

## 6. Findings intentionally not turned into immediate work

### Replace page pagination with cursor/keyset

Not approved as current debt.

The current API contract deliberately uses one-based page pagination. Cursor/keyset may be a better scaling mechanism at larger volumes, but adoption requires evidence that current pagination fails a defined performance criterion and a separate compatibility decision.

Tracked only as an experiment in #85.

### Enable virtual threads

Not approved as a default tuning change.

The technology baseline allows virtual-thread evaluation after a stable synchronous baseline exists. #85 must measure benefit and failure/resource behaviour before retaining the setting.

### Remove PostgreSQL from readiness

Rejected as the default solution.

PostgreSQL is currently a required local dependency for serving catalogue reads. The review correctly raises the risk of an expensive/unbounded health query, but the approved design is better served by a cheap, time-bounded readiness check rather than pretending the application is ready while its required local data store is unusable.

### Remove Spring Data JPA immediately

Deferred.

The release read adapter currently uses JDBC, but JPA/Hibernate is an approved write-side persistence option for later MVP capabilities. Revisit after rating persistence is implemented and the real usage pattern is visible.

### Add arbitrary JaCoCo threshold and OWASP dependency-check

Rejected as proposed.

Coverage percentage is not a proxy for behaviour quality, and the current baseline already contains broader supply-chain controls. Introduce a new gate only when it closes a specific gap and does not merely duplicate existing CI/security tooling.

## 7. Guidance for implementing the follow-ups

For every issue derived from this review:

1. **Revalidate first.** Locate the current code and reproduce or confirm the reported problem. If it no longer exists, close or update the issue with evidence instead of reintroducing the old shape to “fix” it.
2. **Preserve authoritative decisions.** Do not change OpenAPI, readiness semantics, pagination strategy, technology baseline or module boundaries silently.
3. **Prefer focused tests before fixes for defects.** Capture the failure or invariant whenever a deterministic regression test is practical.
4. **Keep changes small and reviewable.** Do not combine unrelated cleanup, performance tuning and product behaviour in one pull request.
5. **Avoid speculative infrastructure.** No Redis, new database, search engine, gateway or distributed component is justified by this review.
6. **Run the complete applicable gates.** At minimum, backend work must pass the repository's supported Maven verification and any affected OpenAPI/documentation checks.
7. **Update this review only for triage/status, not as a competing backlog.** GitHub issues and pull requests own execution status.

## 8. Closure criteria for this review

This review can remain `Triaged` while follow-up issues are open.

It may be marked `Closed` when:

- every `ACCEPT` / `ACCEPT WITH MODIFICATION` item has either been implemented, superseded, merged into another tracked item, or explicitly accepted as residual risk;
- experiments have an `adopt`, `defer` or `reject` result;
- no finding remains as an unowned TODO in this document.

The original review should remain preserved as historical evidence even when every follow-up is complete.
