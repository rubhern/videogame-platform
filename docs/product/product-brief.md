# Product Brief — VideoGame Platform

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Approved:** 2026-08-04
- **Release mode:** Private, non-commercial learning MVP
- **Source:** [Initial vision](../reference/video-game-platform-vision.pdf)

> Product-demand decisions rely on explicitly synthetic evidence and accepted risk.
> The prototype evidence supports an internal interaction decision only; it is not
> real-user research, demand validation, retention evidence, or product–market fit.

## Product and learning outcome

VideoGame Platform is a Spanish-first responsive web product for release-aware
players. It should help a player discover a relevant recent or upcoming game,
understand its platform/region release context, record a personal rating, and find or
maintain that rating later.

The project also develops solution-architecture and technical-leadership capability
through real vertical slices. Learning value does not justify artificial complexity:
technology must support a product outcome, operational need, measured limitation, or
bounded experiment.

Ruben Hernandez is the only human contributor and decision owner. AI may assist but
does not own evidence or approval. There is no fixed beta date, commercial objective,
or recurring paid-service commitment.

## Priority user and problem

The priority user is a release-aware, Spanish-speaking multiplatform player who
researches several games per month and already keeps a wishlist, backlog, rating
history, or similar record.

The selected problem is repeated, fragmented research needed to decide whether a
recent/upcoming release is relevant, combined with personal rating context that is
not always easy to retrieve. This is an accepted learning hypothesis, not a validated
market problem.

The value proposition is:

> Discover what is worth playing now, understand why it may fit you, and keep your
> ratings organised in a clear Spanish-first experience.

The product competes on release relevance and continuity, not catalogue breadth or a
proprietary/professional score.

## Approved MVP

The [story map](mvp-story-map.md) owns the detailed release cut. The MVP includes:

- recent/upcoming releases with platform and region filters;
- title and approved-alias search in a declared bounded catalogue;
- a game page with provider-independent identity, commercial releases, provenance,
  freshness, review status, and explicit date precision;
- an approved provider-CDN cover reference with attribution and a product-owned
  fallback, without copied provider image binaries;
- delegated registration/sign-in at the rating boundary;
- one active integer rating from 1 to 10 per user and released game, with create,
  edit, and delete;
- separately labelled personal and aggregate ratings; aggregate arithmetic mean to
  one decimal, count, and distribution, with Spanish decimal comma and no `/10`;
- `Mis puntuaciones` with search, sort, direct edit, and delete;
- accessibility, security, testing, journey signals, and operability needed by the
  slice.

Deferred: reviews/community, libraries/lists/following, recommendations, professional
scores, prices/stores, exhaustive editions/DLC, native apps, multiple providers,
broad unattended ingestion, public production, and distributed infrastructure.

## Primary journey

1. A visitor browses recent or upcoming releases and optionally filters.
2. They open a game and understand its release context.
3. If released, they choose a rating inline.
4. Authentication occurs only when the rating is confirmed.
5. They return to the same game with personal and aggregate context kept distinct.
6. They later retrieve, edit, or delete the rating in `Mis puntuaciones`.

An unreleased game explains why rating is unavailable and never invents a date or
aggregate value.

## Evidence and decision rules

- **Journey:** `PASS` for the private learning decision. The accepted synthetic round
  reached 4/5 unaided and its focused regression removed the blocking contradiction.
  See the [synthesis](../research/simulated-round-synthesis.md).
- **Provider:** IGDB is a `CONDITIONAL_PASS` for a bounded catalogue. Identity,
  platform, region, provenance, cover reference, offline, security, and operational
  checks passed; release date/precision reached 83.1% against 90%, and localized
  titles 40% against a non-blocking 80%. The owner accepts manual reconciliation and
  product-owned Spanish aliases. See the [PoC result](../research/igdb-poc-results.md).
- **Engineering:** each slice must be automated, tested, observable, documented, and
  operable by one person before a major capability begins.

Signals to observe include release-to-game navigation, rating activation, later
rating retrieval, repeat release use, zero-result/catalogue-boundary failures,
catalogue freshness, synchronization outcome, and journey errors. They guide
learning; they are not market targets.

## Constraints and risks

| Risk | Current response |
|---|---|
| Demand/differentiation unvalidated | Keep claims narrow; use real observations before expanding product scope |
| Provider quality | Bounded catalogue, explicit uncertainty/provenance, manual reconciliation, local reads |
| Spanish data gaps | Product-owned aliases/editorial content; never present translation as provider content |
| Provider/licensing scope | Private non-commercial use only; direct attributed IGDB CDN references; no copied images or external scores |
| Provider coupling/outage | Internal identity, anti-corruption adapter, last valid local snapshot, no request-path IGDB |
| Scope/architecture expansion | One journey and modular monolith; complexity requires evidence or bounded learning objective |
| Accounts/personal ratings | Delegated identity, principal-derived ownership, privacy/security controls |
| Solo operation | Small increments, automation, few deployables, zero recurring-cost constraint |

Public deployment, monetization, copied/application-stored/redistributed provider
data or images, broad unattended synchronization, or material provider-term changes
reopen the provider and release-mode decisions before deployment.

## Long-term direction

The source vision includes broader catalogue, libraries, reviews, community,
recommendations, external scores, analytics, and distributed-system learning. These
are directional possibilities, not committed roadmap. Each must earn inclusion
through evidence, value, cost, risk, and operability.
