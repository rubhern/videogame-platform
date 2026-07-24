# Product Brief — VideoGame Platform

- **Status:** Approved
- **Version:** 0.3
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-24
- **Phase:** 0 — Product alignment (complete)
- **Primary source:** [VideoGame Platform vision](../reference/video-game-platform-vision.pdf)
- **Research inputs:** [Synthetic interview preparation](../research/phase-1-user-interviews.md), [Metacritic journey comparison](../research/competitor-journey-comparison-metacritic.md), [game-data-provider spike](../research/game-data-providers-spike.md), and [first authenticated IGDB PoC](../research/igdb-poc-results.md)
- **Sources reviewed:** 2026-07-24

> This is a personal learning project. Product-demand decisions based on synthetic
> research are explicit accepted risks, not claims of real user evidence or
> product–market fit.

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
- Registration and sign-in through an established identity approach.
- Create, change, and remove one integer rating from 1 to 10 per user and game.
- Aggregate arithmetic mean to one decimal, rating count, and distribution.
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
4. They register or sign in when they decide to rate it.
5. They create or update their rating.
6. They see their rating and the aggregate context.
7. They retrieve, edit, or delete it later from `Mis puntuaciones`.

## 12. Hypotheses

The prioritised hypothesis register is maintained in
[assumptions.md](assumptions.md). All demand-related hypotheses are accepted risks for
the learning MVP, not supported findings. The most consequential concern:

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
  learning scope, using local normalized metadata without copied provider images or
  external ratings. Public deployment, monetization, copied images, redistribution,
  or broad unattended synchronization must reopen the gate and clarify partnership,
  attribution, retained-data, and image requirements.
- **Journey gate:** in one lightweight round with five representative users, at least
  four should complete release discovery → game page → rating → `Mis puntuaciones`
  without assistance or a blocking usability problem. If not, iterate before adding
  scope.
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
| Data licensing and availability | Documentary fit does not settle every public, commercial, attribution, or image-use condition | Record the terms for the non-commercial PoC; obtain any required partnership and clarify attribution, retained data, and images before public or monetized release |
| Provider data quality | The first authenticated PoC passed core identity, platform, region, provenance, and operational metrics, but release date/precision reached 83.1% | Keep the catalogue bounded; separate release from subscription availability; preserve precision and provenance; reconcile displayed recent/upcoming dates against an official source |
| Spanish provider content gap | Localized-title coverage reached 40%, and neither provider guarantees complete Spanish editorial content | Maintain product-owned Spanish aliases, keep Spanish editorial content separate from provider summaries, and record source language explicitly |
| Weak differentiation | A correct catalogue may still be irrelevant to users | Compare the core task with Metacritic or a tracker in a lightweight prototype test |
| Scope expansion | The long-term vision can overwhelm the first validation | Approve an explicit in/out MVP boundary |
| Provider coupling | An external model could dictate the internal product model | Use internal identifiers and isolate provider-specific concepts when implementation begins |
| Premature architecture | Operational cost may grow without corresponding product value | Prefer the smallest deployable architecture that supports the selected journey |
| User-generated content | Reviews and community features introduce abuse, privacy, and moderation duties | Keep them outside the first MVP |
| Solo ownership | Delivery, review, and operations depend on one person | Keep increments small, automate repeatable checks, and avoid simulated process overhead |
| Synthetic evidence | Fictional interviews can create false confidence | Label demand assumptions as accepted risks and use real users only for small usability checks |

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
**Q-005 (provider and licence feasibility)** selects IGDB with explicit limitations.
Public deployment, monetization, copied images, or redistribution are new release
modes that must reopen the provider gate; they are not unresolved requirements for
closing this Product Brief.

## 17. Approval record

| Role | Person | Decision | Date |
|---|---|---|---|
| Product owner | Ruben Hernandez | Approved v0.3 and closed Phase 0 | 2026-07-24 |
| Technical lead | Ruben Hernandez | Approved with documented IGDB limitations | 2026-07-24 |

The two rows represent simulated responsibilities held by the same person, not
independent approval authorities.

## 18. Phase 0 closure and minimum next steps

The Product Brief is complete for the current learning scope. The priority user,
problem, value proposition, primary journey, MVP boundary, owner, provider decision,
accepted risks, and success rules are explicit. No additional market study, second
provider PoC, provider ADR, public-release legal work, or detailed backlog is required
to close Phase 0.

The first next step is complete: the primary journey, MVP release cut, acceptance
checks, guardrails, and deferred scope are captured in the
[learning MVP story map](mvp-story-map.md).

Remaining minimum next steps:

1. Create one mobile-first clickable prototype using a transparent bounded sample.
2. Run one lightweight usability round with five representative users and revise only
   blocking journey or comprehension problems.
3. Define the minimum provider-independent domain/API contracts and implement one
   end-to-end vertical slice with authentication, tests, structured logs, catalogue
   freshness, journey metrics, and a simple deployment path.

If the release mode changes from private learning to public or commercial use,
reopen Q-005 and the provider release-mode gate before deployment.
