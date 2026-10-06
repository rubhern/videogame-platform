# Product Brief — Gameómetro

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Approved MVP baseline:** 2026-08-04
- **Current release mode:** Private, non-commercial `dev`
- **Source:** [Initial vision](../reference/video-game-platform-vision.pdf)

> Product-demand decisions rely on explicitly synthetic evidence and accepted risk.
> The prototype evidence supports an internal interaction decision only; it is not
> real-user research, demand validation, retention evidence, or product–market fit.

## Product and learning outcome

Gameómetro is an evolving Spanish-first responsive web product for release-aware
players. The learning MVP is complete and accepted in private `dev` (`v0.1.0`);
post-MVP work develops the real product while retaining that baseline. `VideoGame
Platform` remains the repository and system name.

The direction is to do a few things extremely well: fast, visually clear release
discovery and calendar awareness, useful game information with high information
density and low friction, trustworthy quality signals, and personal ratings/history
with later personal insights. Spanish-first means conceived for Spanish-speaking
players, with natural Spanish terminology and presentation, rather than an
English-first product translated afterwards.

The project also develops solution-architecture and technical-leadership capability
through real vertical slices. Learning value does not justify artificial complexity:
technology must support a product outcome, operational need, measured limitation, or
bounded experiment.

Ruben Hernandez is the only human contributor and decision owner. AI may assist but
does not own evidence or approval. There is no fixed public-release date or recurring
paid-service commitment. Public release and commercial exploration are described
[below](#public-release-and-sustainability).

## Priority user and problem

The priority user is a release-aware, Spanish-speaking multiplatform player who
researches several games per month and already keeps a wishlist, backlog, rating
history, or similar record.

The selected problem is repeated, fragmented research needed to decide what is
available or coming and which games are worth playing, combined with personal rating
context that is not always easy to retrieve. The direction extends release awareness
toward useful quality judgement. The problem and expected value remain hypotheses;
real demand has not been validated.

The value proposition is:

> Discover what is coming or available, judge which games are worth playing, and keep
> your ratings organised in a clear Spanish-first experience.

Positioning centres on release clarity, trustworthy player/community signals, and
personal continuity. Becoming a broad gaming/news portal is outside this direction.

## Approved MVP

The [story map](mvp-story-map.md) owns the accepted MVP journey and release cut. Its
capabilities remain the baseline for post-MVP evolution:

- recent/upcoming releases with platform and region filters;
- title and approved-alias search across the local catalogue;
- a game page with provider-independent identity, commercial releases, provenance,
  freshness, review status, and explicit date precision;
- an approved provider-CDN cover reference with attribution and a product-owned
  fallback, without copied provider image binaries;
- delegated registration/sign-in at the rating boundary;
- one active integer rating from 1 to 10 per user and released game, with create,
  edit, and delete;
- separately labelled personal and aggregate ratings; aggregate arithmetic mean to
  one decimal, count, and distribution, with Spanish decimal comma; personal and
  aggregate readings consistently retain the `/10` scale and existing score-temperature
  labels (owner presentation decision, #234). The temperature presents the score; it
  never changes its meaning. Compact rating collections need only mean and count;
- `Mis puntuaciones` with search, sort, direct edit, and delete;
- accessibility, security, testing, journey signals, and operability needed by the
  slice.

The catalogue itself is acquired from the provider rather than hand-built: bounded,
unattended synchronization discovers and imports new works and reconciles known ones
whenever they appear in an operator-requested date interval, under an explicit import
policy. Manual curation of each new game is no
longer a premise. See
[ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md).

Outside the accepted MVP cut: written reviews/social features, libraries/lists/
following, recommendations, external scores, prices/stores, exhaustive editions/DLC,
native apps, multiple providers, exhaustive historical catalogue coverage, public
production, and distributed infrastructure. Direction below does not add these to
the accepted release or make them committed delivery requirements.

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
  titles 40% against a non-blocking 80%. The owner accepts explicit provenance and
  review state on uncertain release evidence, and product-owned Spanish aliases. See
  the [PoC result](../research/igdb-poc-results.md).
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
| Provider quality | Explicit import policy, validation before publication, explicit uncertainty/provenance/review state, local reads |
| Spanish data gaps | Product-owned aliases/editorial content; never present translation as provider content |
| Provider/licensing scope | Current approval covers private non-commercial use and attributed IGDB CDN references; no copied image binaries or adopted external scores; public use and external signals require review |
| Provider coupling/outage | Internal identity, anti-corruption adapter, last valid local snapshot, no request-path IGDB; provider drives acquisition, never serving |
| Scope/architecture expansion | Focused product journeys and modular monolith; complexity requires evidence or bounded learning objective |
| Accounts/personal ratings | Delegated identity, principal-derived ownership, privacy/security controls |
| Solo operation | Small increments, automation, few deployables, zero recurring-cost constraint |

Public deployment, monetization, provider-data retention or redistribution beyond
the approved normalized local catalogue and CDN-reference mode, image binary
copying/storage, acquisition beyond the bounded synchronization
[ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
approves, or material provider-term changes reopen the provider and release-mode
decisions before deployment.

## Trustworthy quality and community direction

The long-term first-party quality signal is the **Gameómetro community rating**.
Personal ratings and their aggregate already exist; a useful community signal at
scale remains a direction, without evidence of a real community or demand yet.

- A Gameómetro score represents only ratings from Gameómetro users. Never fabricate,
  estimate, or derive it from press or external scores.
- External scores may help the early community cold start if their use is approved;
  preserve visible source/type and meaningful rating counts. Personal and community
  ratings remain distinct, and user and critic scores never become one opaque number.
- Missing scores and small samples must be presented honestly, without implying
  precision or consensus the evidence does not support.
- Player/community sentiment fits the product direction most closely; professional
  criticism is complementary.
- Evaluate IGDB first because it is already integrated. OpenCritic or another
  provider needs material additional value and permitted usage.

Evaluation and delivery details belong to
[#256](https://github.com/rubhern/videogame-platform/issues/256) and
[#255](https://github.com/rubhern/videogame-platform/issues/255), respectively; no
external-score source, display threshold, or implementation is adopted here.

## Public release and sustainability

A public Gameómetro environment is an intended next evolution; the deployed
environment remains private `dev`. Public production is neither approved nor
available: provider/licensing, privacy, security, capacity, cost, operations, and
release-mode decisions must pass the gates tracked by
[#251](https://github.com/rubhern/videogame-platform/issues/251) and
[#252](https://github.com/rubhern/videogame-platform/issues/252) before exposure.
The [platform design](../architecture/deployment/mvp-platform-and-delivery.md) owns
the environment boundaries.

Commercial sustainability is exploratory. A plausible initial goal is a small
community of real users and modest, non-intrusive monetization that offsets basic
operating costs while preserving a clean, low-friction experience. This is not
validated demand, product–market fit, a revenue target, a committed business roadmap,
or an adopted monetization model. Paid infrastructure still requires an explicit
owner decision; the current zero-recurring-cost constraint remains binding.

Broader vision capabilities must earn inclusion through evidence, value, cost, risk,
and solo operability; GitHub Issues own evaluation and delivery work.
