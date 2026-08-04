# Product Brief — VideoGame Platform

- **Status:** Approved
- **Version:** 0.9
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-08-04
- **Phase:** 0 — Product alignment (complete)
- **Primary source:** [VideoGame Platform vision](../reference/video-game-platform-vision.pdf)
- **Research inputs:** [Synthetic interview preparation](../research/phase-1-user-interviews.md), [Metacritic journey comparison](../research/competitor-journey-comparison-metacritic.md), [game-data-provider spike](../research/game-data-providers-spike.md), [first authenticated IGDB PoC](../research/igdb-poc-results.md), and [accepted simulated usability round](../research/simulated-round-synthesis.md)
- **Prototype:** [Mobile-first clickable prototype](clickable-prototype.md)
- **Sources reviewed:** 2026-08-04

> This is a personal learning project. Product-demand decisions based on synthetic
> research are explicit accepted risks. The owner accepts the simulated usability
> round for the internal journey decision, but it is not evidence from real users,
> product demand, or product–market fit.

## 1. Executive summary

VideoGame Platform is a Spanish-first responsive web product for release-aware
players. It helps them discover relevant recent or upcoming games, understand
platform and regional release context, and retain their own ratings. The long-term
vision may include richer catalogue, personal, community, recommendation, and
analytical capabilities, but none are commitments.

The approved learning MVP is one complete journey: discover a release, open a clear
game page, record a rating, and retrieve it from `Mis puntuaciones`. It deliberately
uses a bounded catalogue, one authorised data provider, and no professional-review
aggregation, written reviews, community, or recommendation engine.

The first authenticated IGDB PoC produced a reviewed `CONDITIONAL_PASS`. It supports
IGDB as the initial provider for a bounded, curated learning catalogue, with manual
reconciliation of ambiguous release dates and product-owned Spanish aliases. This is
a technical decision for the personal learning MVP, not approval for broad,
unattended, public, or commercial use.

For this private release mode, approved covers may be loaded directly from the IGDB
image CDN using stored provider references, visible attribution, and a product-owned
fallback. Provider image binaries are not copied, proxied, persisted, committed, or
redistributed by VideoGame Platform.

The approved journey is represented in a medium-fidelity
[mobile-first clickable prototype](clickable-prototype.md) with eight transparently
curated games. All eight game pages are navigable, while one representative game
contains the complete simulated rating journey. The accepted simulated round reached
four of five unaided completions. A focused simulated regression verified the
evidence-backed revisions and resolved the blocking rating-state inconsistency, so
the journey decision is `PASS`.

## 2. Product purpose

The product aims to give Spanish-speaking, release-aware players a clear place to:

- discover relevant releases for their platforms and region;
- consult concise, current, and provenance-aware information about a game;
- express, retrieve, and maintain a personal rating;
- eventually build a richer history of their relationship with video games.

The product should solve a focused user problem before expanding into reviews,
social features, recommendations, advanced rankings, or comprehensive catalogue
management.

## 3. Learning purpose

The primary purpose of the initiative is to improve solution-architecture and
technical-leadership capability through the progressive design and construction of
a complete platform. It should develop practical experience in:

- turning a broad vision into validated product increments;
- software architecture, Domain-Driven Design, and hexagonal architecture;
- delivering thin vertical slices from user interface to operations;
- modelling a catalogue without coupling the domain to an external provider;
- designing explicit, stable API, event, and data contracts;
- distributed systems, asynchronous processing, and event-driven architecture when
  justified;
- applying security, testing, observability, and automation from the start;
- persistence, identity, resilience, performance, and cloud-native delivery;
- technical team leadership and architectural decision-making;
- documenting significant decisions and evolving architecture incrementally.

Architectural learning has explicit priority, but it must support product outcomes or
a deliberate learning objective. It is not justification for artificial complexity.

### 3.1 Project operating model

- Ruben Hernandez is the sole project owner, product owner, technical lead, and human
  contributor.
- AI may assist with simulated stakeholder, research, design, engineering, review,
  and operational roles. It has no independent ownership or approval authority.
- The project simulates realistic team practices only when they create learning value.
  It does not create fictional dependencies, hand-offs, or approval gates.
- There is no externally committed beta date. Capacity, schedule, and spend are set
  one vertical slice at a time by Ruben Hernandez.
- The current phase is learning-only. Commercial viability and exhaustive market
  research are outside scope.

## 4. Long-term vision

VideoGame Platform may evolve into a trusted product for video-game discovery,
tracking, ratings, and analysis. Potential future capabilities include:

- a broad game and release catalogue;
- personal libraries, lists, and following;
- written reviews and community interaction;
- professional or external ratings where licensing permits their use;
- recommendations, rankings, statistics, and trend analysis;
- moderation and editorial administration.

This is directional context, not committed scope. Each capability must earn its place
through evidence, value, cost, risk, and operational viability.

## 5. Context and opportunity

**Status: Accepted learning hypothesis.**

Release information, game details, ratings, and personal tracking already exist
across stores, media, communities, trackers, and aggregate sites. The reviewed
[competitor journey](../research/competitor-journey-comparison-metacritic.md#15-product-decisions-suggested-by-this-comparison)
suggests a narrower opportunity: a Spanish-first, game-only experience that combines
relevant release context with a retrievable personal rating history.

The product will not claim superior demand, catalogue breadth, or market fit. A small
comparative prototype test is sufficient for learning; an exhaustive market study is
not required.

## 6. Initial product problem

Release-aware, Spanish-speaking multiplatform players repeatedly combine several
sources to determine whether a recent or upcoming game is relevant to them. Release
and platform context can be inconsistent or detached from the player's prior
decision, and existing rating flows do not always provide a useful, retrievable
personal history.

This is the problem chosen for the learning MVP. The [synthetic interview
synthesis](../research/phase-1-user-interviews.md#6-synthetic-synthesis) makes it
plausible but does not validate it with real users.

## 7. Priority user

The first user is a **release-aware, Spanish-speaking multiplatform player who
researches several games per month and already maintains a wishlist, backlog, rating
history, or similar personal record**.

The MVP does not target players focused on one live-service game, professional
critics, creators, studios, publishers, moderators, or users seeking a comprehensive
social community. Those audiences must not shape the first journey.

## 8. Initial value proposition

> **Discover what is worth playing now, understand why it may fit you, and keep your
> ratings organised in a clear Spanish-first experience.**

The initial advantage is relevance and continuity, not a proprietary score:

- platform- and region-aware release context;
- concise game information with explicit provenance and freshness;
- a complete rating loop through `Mis puntuaciones`;
- a focused game-only interface in Spanish.

## 9. Objectives

### Product objectives

- Deliver the selected journey as one small, observable vertical slice.
- Use the conditionally approved IGDB integration to learn from a bounded catalogue,
  while preserving release provenance, date precision, and explicit manual-review
  state.
- Learn where users fail, leave for another source, or return to their ratings.
- Preserve an explicit boundary between accepted learning risks and validated facts.

### Learning objectives

- Practise incremental product discovery and delivery.
- Establish clear domain boundaries without premature distribution.
- Make delivery, quality, security, and operational concerns part of each slice.

## 10. Learning MVP boundary

**Status: Approved.**

### Included

- Spanish-first interface and product copy.
- Recent and upcoming release view with basic platform and region filters.
- Title and alternative-title search within an explicitly bounded catalogue.
- A game page with essential catalogue, platform-release, provenance, and freshness
  information.
- A recognizable primary cover loaded from an approved provider CDN reference, with
  visible attribution and a product-owned fallback.
- Registration and sign-in through an established identity approach.
- Create, change, and remove one integer rating from 1 to 10 per user and game.
- Inline 1–10 rating selection in the game or personal-list context; no separate
  rating page.
- Ratings only for games that have been released.
- Aggregate arithmetic mean to one decimal, rating count, and distribution. Spanish
  product copy uses a decimal comma and never adds a denominator such as `/10`.
- Aggregate and personal ratings are always labelled and displayed separately
  wherever rating context appears. An unreleased game shows its aggregate as
  unavailable rather than inventing a numeric value.
- Minimal `Mis puntuaciones` view with search, sorting, direct edit, and delete.
- Import from one game-data provider: IGDB is technically approved with documented
  limitations for bounded personal learning use; RAWG remains only a fallback.
- Basic analytics and operational visibility for the core journey.

### Deferred

- Written reviews, comments, social features, and advanced moderation.
- Personal libraries, custom lists, and following.
- Recommendations and complex rankings.
- Professional reviews or third-party scores without explicit authorised access.
- Detailed editions, DLC, expansions, and exhaustive technical requirements.
- Native mobile applications.
- Multiple simultaneous game-data providers.
- Platform-specific rating aggregates, opaque weighting, verified-play claims, and
  advanced anti-manipulation mechanisms.
- Prices, store comparison, or professional-review aggregation.
- Microservices, event streaming, data lakes, and multi-region deployment.

## 11. Primary journey

1. A visitor opens the release view.
2. They filter by platform or region if needed.
3. They select a game and read its game page.
4. If the game has been released, they open the inline rating selector.
5. They register or sign in only when they confirm a rating.
6. They return to the same game context and see their personal rating separately
   from the aggregate context.
7. They retrieve, edit, or delete it later from `Mis puntuaciones`.

For an unreleased game, the personal-rating selector remains disabled and the page
explains that rating becomes available after release.

## 12. Hypotheses

The prioritised hypothesis register is maintained in
[assumptions.md](assumptions.md). Demand-related hypotheses remain accepted risks,
not supported findings. The simulated usability round can support internal
interaction decisions for this learning project, but not demand claims. The most
consequential concern:

- the relevance of release discovery for the chosen segment;
- the value of combining discovery, information, rating, and later retrieval;
- Spanish-first regional and platform relevance;
- the availability and licensing of external catalogue data;
- the credibility of a deliberately bounded catalogue.

## 13. Success and decision rules

Success means completing a useful learning increment, not demonstrating commercial
traction.

### Required gates

- **Provider technical gate:** conditionally passed on 2026-07-24. The
  [first authenticated execution](../research/igdb-poc-results.md) passed the
  essential identity, platform, region, provenance, offline, security, and
  operational checks. Release date and precision reached 83.1% against a 90%
  threshold, and localized-title coverage reached 40% against a non-blocking 80%
  threshold. The frozen thresholds remain unchanged. Ruben Hernandez accepts these
  limitations for a bounded catalogue with manual reconciliation, explicit
  uncertainty, and product-owned Spanish aliases.
- **Provider release-mode gate:** passed for the current private, non-commercial
  learning scope, using local normalized metadata and direct IGDB CDN cover
  references without copied provider image binaries or external ratings. Provider
  covers require visible attribution, a clear source path, allowlisted delivery, and
  a product-owned fallback. Public deployment, monetization, copied images,
  redistribution, application-managed image storage, or broad unattended
  synchronization must reopen the gate and clarify partnership, attribution,
  retained-data, and image requirements.
- **Journey gate:** `PASS` on 2026-07-28. By explicit owner decision, the
  [five-session simulated round](../research/simulated-round-synthesis.md) is accepted
  as decision-grade evidence for this private learning project. Four of five
  simulated participants completed release discovery → game page → rating →
  `Mis puntuaciones` unaided. The focused simulated regression confirmed that F-01
  is resolved, all pre-rating entry points show `Sin puntuar`, and no blocking issue
  remains in the corrected path. This decision does not claim external user
  validation.
- **Engineering gate:** the vertical slice is automated, tested, observable,
  documented, and operable by one person before starting the next major capability.

### Signals to observe

- proportion of searches or release views that lead to a game page;
- proportion of registered users who rate at least one game;
- proportion of raters who later open `Mis puntuaciones`;
- repeat use of the release view;
- user success and observed friction in usability sessions;
- zero-result searches and catalogue-boundary failures;
- catalogue freshness and successful synchronisation rate;
- error rate across the primary journey.

These signals guide learning and prioritisation. They are not market-size or
product–market-fit thresholds.

## 14. Risks and constraints

| Risk | Why it matters | Initial response |
|---|---|---|
| Data licensing and availability | Documentary fit supports referenced CDN covers for the private MVP but does not settle every public, commercial, copied-image, retention, or redistribution condition | Use allowlisted direct provider references with visible attribution and fallback; obtain any required partnership before public, monetized, copied, stored, or redistributed image use |
| Provider data quality | The first authenticated PoC passed core identity, platform, region, provenance, and operational metrics, but release date/precision reached 83.1% | Keep the catalogue bounded; separate release from subscription availability; preserve precision and provenance; reconcile displayed recent/upcoming dates against an official source |
| Spanish provider content gap | Localized-title coverage reached 40%, and neither provider guarantees complete Spanish editorial content | Maintain product-owned Spanish aliases, keep Spanish editorial content separate from provider summaries, and record source language explicitly |
| Weak differentiation | A correct catalogue may still be irrelevant to users | Compare the core task with Metacritic or a tracker in a lightweight prototype test |
| Scope expansion | The long-term vision can overwhelm the first validation | Approve an explicit in/out MVP boundary |
| Provider coupling | An external model could dictate the internal product model | Use internal identifiers and isolate provider-specific concepts when implementation begins |
| Premature architecture | Operational cost may grow without corresponding product value | Prefer the smallest deployable architecture that supports the selected journey |
| User-generated content | Reviews and community features introduce abuse, privacy, and moderation duties | Keep them outside the first MVP |
| Solo ownership | Delivery, review, and operations depend on one person | Keep increments small, automate repeatable checks, and avoid simulated process overhead |
| Synthetic evidence | Fictional interviews and simulated sessions can create false confidence | Preserve synthetic provenance; use the accepted round only for internal learning-project decisions; never claim real-user behaviour, demand validation, or product–market fit |

The project has one human contributor, no committed beta date, and no current
commercial objective. Privacy, security, accessibility, and provider licensing still
apply because the MVP includes accounts, ratings, and external data.

## 15. Product and technology principles

- Start with one user and one meaningful problem.
- Deliver capabilities as complete vertical slices.
- Prefer simple, modular, observable solutions.
- Keep domain logic independent from external providers and delivery technology.
- Treat security, privacy, accessibility, testing, and operations as product quality.
- Make data provenance and synchronisation state explicit.
- Add complexity only in response to demonstrated needs.
- Introduce technology when it delivers product value or a relevant, explicit
  learning outcome.
- Record significant, durable decisions; avoid documentation ceremony.

## 16. Open questions

The live register is [open-questions.md](open-questions.md). All Phase 0 product
questions are resolved for the approved private, non-commercial learning scope.
**Q-005 (provider and licence feasibility)** selects IGDB with explicit limitations
and permits referenced CDN covers under
[ADR-0001](../decisions/0001-reference-igdb-cover-images.md). Public deployment,
monetization, copied or stored provider images, or redistribution are new release
modes that must reopen the provider gate; they are not unresolved requirements for
closing this Product Brief. **Q-010 (prototype evidence standard)** accepts the
simulated round only for the private learning-project journey decision and preserves
its synthetic provenance.

## 17. Approval record

| Role | Person | Decision | Date |
|---|---|---|---|
| Product owner | Ruben Hernandez | Approved v0.3 and closed Phase 0 | 2026-07-24 |
| Product owner | Ruben Hernandez | Approved v0.4 rating interactions and prototype validation artefact without expanding the MVP | 2026-07-27 |
| Product owner | Ruben Hernandez | Approved v0.5 acceptance of the simulated five-session round for the private learning-project journey decision; result `ITERATE` | 2026-07-28 |
| Product owner | Ruben Hernandez | Approved v0.6 closure of the prototype and simulated usability gate after focused regression; result `PASS` | 2026-07-28 |
| Product owner | Ruben Hernandez | Approved v0.7 provider-hosted cover references and the minimum domain model without expanding the release mode | 2026-07-29 |
| Product owner | Ruben Hernandez | Approved v0.8 reconciliation with the Phase 1 platform and delivery records without changing product scope or release mode | 2026-08-03 |
| Product owner | Ruben Hernandez | Approved v0.9 technology baseline and closed Phase 1 MVP solution definition without claiming implementation evidence | 2026-08-04 |
| Technical lead | Ruben Hernandez | Approved with documented IGDB limitations | 2026-07-24 |

These rows preserve the version and responsibility history. Every decision is held
by the same person, not by independent approval authorities.

## 18. Phase 0 closure and implementation transition

The Product Brief is complete for the current learning scope. Version 0.3 closed
Phase 0; version 0.4 records the owner-directed rating interaction rules; version 0.5
records the accepted simulated usability round and its `ITERATE` decision without
changing the approved boundary; version 0.6 records the corrected prototype, focused
regression, and `PASS` decision; version 0.7 records the provider-hosted cover policy
and approved minimum domain contract; version 0.8 reconciles the closed product record
with the accepted Phase 1 platform and delivery decisions without changing the MVP.
Version 0.9 records the approved technology baseline and closure of Phase 1 MVP
solution definition. It does not claim that the walking skeleton, multi-architecture
image, or remote OCI environment has been implemented.
The priority user, problem, value proposition, primary journey, MVP boundary, owner,
provider decision, accepted risks, and success rules are explicit. No additional market
study, second provider PoC, public-release legal work, or detailed backlog is required
to close Phase 0.

The first three product-discovery steps are complete:

1. the primary journey, MVP release cut, acceptance checks, guardrails, and deferred
   scope are captured in the [learning MVP story map](mvp-story-map.md);
2. the journey and its critical states are represented in the
   [mobile-first clickable prototype](clickable-prototype.md);
3. the five-session [simulated usability round](../research/simulated-round-synthesis.md)
   has been accepted for the internal learning-project decision.

The prototype and accepted simulated usability work are complete for the current
learning objective. Reopen them only if the release mode changes, a material journey
rule changes, or implementation reveals a new blocking usability risk.

The provider-independent domain, application, solution-architecture, REST, OpenAPI,
platform, delivery-lifecycle, technology-baseline, and durable implementation
decisions required before implementation are approved. Phase 1 MVP solution
definition is complete. Application implementation is active at the walking-skeleton
gate.

Minimum next steps:

1. Prove the approved baseline with the smallest local walking skeleton: application
   startup, PostgreSQL and Keycloak, one migration, deterministic seed data, version,
   liveness/readiness, CI tests, and explicit `linux/amd64` and `linux/arm64`
   container evidence.
2. Implement the release page → game page → rating → `Mis puntuaciones` slice
   incrementally, preserving the approved OpenAPI, security, accessibility,
   observability, provider, and recovery boundaries.
3. Build and scan the immutable multi-architecture image before provisioning reviewed
   Always Free infrastructure, then validate the running slice on a real phone-sized
   browser and through its failure, backup, restore, and rollback paths.

If the release mode changes from private learning to public or commercial use,
reopen Q-005 and the provider release-mode gate before deployment.
