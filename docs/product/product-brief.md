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
does not own evidence or approval. There is no fixed public-release date; the only
paid commitment is the public domain registered for that release. The owner-approved
bounded budget, deferred purchase, public release and commercial exploration are described
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
  review status, and explicit date precision; synchronization freshness stays internal
  operational evidence and is not shown to players;
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
| Provider/licensing scope | Private non-commercial baseline remains; the current public-use risk treatment is recorded [below](#public-release-and-sustainability); the external-score direction below is approved, with acquisition, local storage and discovery presentation implemented (#255, awaiting review), and unresolved rights under #274; no copied game image binaries |
| Provider coupling/outage | Internal identity, anti-corruption adapter, last valid local snapshot, no request-path IGDB; provider drives acquisition, never serving |
| Scope/architecture expansion | Focused product journeys and modular monolith; complexity requires evidence or bounded learning objective |
| Accounts/personal ratings | Delegated identity, principal-derived ownership, privacy/security controls |
| Solo operation | Small increments, automation, few deployables; bounded public-release budget and best-effort expectations [below](#public-release-and-sustainability) |

Public deployment, monetization, provider-data retention or redistribution beyond
the approved normalized local catalogue and CDN-reference mode, image binary
copying/storage, acquisition beyond the bounded synchronization
[ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
approves, or material provider-term changes reopen the provider and release-mode
decisions before deployment.

## Personal rating insights

**Post-MVP, owner-requested and implemented; awaiting review (#254):**
**Tus estadísticas** gives a signed-in player a visual way to inspect their own
recorded ratings and, post-MVP (#363, implemented; awaiting review), the platforms they
recorded playing them on. Its value is a hypothesis: recognising patterns may make
maintaining a personal collection more useful. It makes no claim about personality,
playing time, ownership, inferred platforms or recommendations. Sparse samples remain
descriptive. The
[application use case](../architecture/application/mvp-use-cases.md#personal-rating-statistics-uc-015-post-mvp-254)
owns the selected statistics and calculation guarantees; the accepted MVP cut is unchanged.

## Personal discovery preferences

**Approved; implemented, awaiting owner review (#295):** a signed-in player may choose the
platforms they play on and have Destacados and Explorar lanzamientos show those first; the
**Mis plataformas** checkbox in **Mi cuenta** sets them aside for both screens at once. The choice is explicit, optional,
skippable and editable in **Mis plataformas**; it narrows release discovery only, never search,
game pages or `Mis puntuaciones`. It is not a recommendation engine, an inference from ratings,
an ownership inventory or a general profile; genre preferences and follows remain outside this
direction. Whether players use it is a hypothesis to observe, not yet evidence.

## Trustworthy quality and community direction

The long-term first-party quality signal is the **Gameómetro community rating**.
Personal ratings and their aggregate already exist; a useful community signal at
scale remains a direction, without evidence of a real community or demand yet.

- A Gameómetro score represents only ratings from Gameómetro users. Never fabricate,
  estimate, or derive it from press or external scores.
- **Owner-approved; implemented, awaiting owner review (#359):** a player may rate a game
  directly or per platform played (PlayStation 5, PC, Switch…), optionally declaring
  platforms without a score. Each player still casts one vote: the direct score or the mean
  of their scored platforms, so rating several platforms never adds weight. The game page
  keeps the community score as its primary reading and offers each platform's own mean and
  number of scorers as secondary context, a basis for later platform statistics or
  rankings, which remain unapproved. The
  [domain model](../architecture/domain/mvp-domain-model.md#rating-policies) owns the rules
  and [ADR-0031](../decisions/0031-count-one-community-vote-per-rating-across-platforms.md)
  the rejected alternatives.
- **Approved; acquisition and discovery presentation implemented, awaiting review:** adopt IGDB player scores
  (`rating` and `rating_count`) as a complement. #255 acquires and stores them locally, then presents them alongside the
  Ratings-owned community signal on release-discovery cards. #279 phase 3 adds them to the
  game page as a separate reading, and its phase 4 to full search results and, as secondary
  context beside the personal rating, to `Mis puntuaciones` (all implemented, awaiting review);
  the search typeahead keeps them absent, and the remaining screens are still pending phase 4.
  Defer IGDB critic scores and OpenCritic; reject
  combined `total_rating` scores. For the non-commercial public direction, the owner accepts the unresolved
  rights risk as recorded [below](#public-release-and-sustainability); commercial use
  remains unapproved.
- Always show each available valid score with its source and positive vote count,
  including a single vote. No minimum sample threshold may hide either Gameómetro
  or IGDB scores. The one owner-approved threshold (2026-10-26, #386; lowered 2026-10-27) decides
  ranking only: TOPómetro ranks IGDB players' scores from 50 ratings and community scores from the first,
  while every such score stays shown wherever it appears. Keep the two sources separate; sample size never changes provenance
  or blends their values. Personal and community ratings also remain distinct.
- Missing or invalid score/count pairs have no numeric score; unavailable statistics
  must not be presented as zero votes. Counts communicate sample size without
  implying consensus or statistical confidence.
- Keep observation time for traceability. Age alone never expires or hides a score:
  information not checked recently is not evidence of incorrect information. No
  seven-day score expiry or automatic age-based suppression is approved.
- The purposive [#256 sample](../research/external-quality-signals.md#authenticated-sample)
  cannot establish coverage of the complete catalogue. #255 must measure a
  representative sample of the actual catalogue during implementation and record
  selection, coverage, missingness and limitations.

The [research record](../research/external-quality-signals.md) owns provider evidence
and its limitations. [#255](https://github.com/rubhern/videogame-platform/issues/255)
owns delivery of this approved direction; its first phase covers acquisition and local
storage only.

## Public release and sustainability

A public Gameómetro environment is the owner-approved next release direction:
**open, non-commercial and best effort**, without a commercial SLA or staffed support.
The deployed environment remains private `dev`; production is unbuilt and exposure
is not authorized by this direction alone. **Owner decision, 2026-10-25 (#276):**
select netcup VPS Lite 3 instead of Lite 2, subject to final contractual quote and
real-host qualification. The **total recurring ceiling is EUR 25/month**, including
VAT, domain renewal, email, storage, administration and every recurring extra. This
supersedes the prior EUR 20 ceiling and earlier zero-recurring-cost restriction for
the gated public environment. The [#276 budget evidence](../research/public-production-backup-storage.md#complete-recurring-total-with-lite-3)
includes the EUR 2 email contingency; estimated charges are not account-specific
quotes or enforceable caps. The owner accepts the residual risk of uncapped OVHcloud
Object Storage PAYG charges as a scoped exception to the capped-overage gate
(2026-10-25), so costs above EUR 25 remain possible. This authorizes no contracting
or exposure; the [contracting gate](../development/delivery-lifecycle.md#public-production-gates)
owns its conditions.
The owner registered the domain `gameometro.com` for this release ahead of the VPS; the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#public-domain-and-names)
owns the approved public names.

Delay contracting the VPS until a production candidate has passed preparation in
local/CI. Keep developing in local/CI and use private-dev temporarily for integration;
review its retirement after public acceptance rather than relabel it production.
Provider/licensing, privacy, security, capacity, cost and recovery gates are tracked by
[#251](https://github.com/rubhern/videogame-platform/issues/251) and
[#252](https://github.com/rubhern/videogame-platform/issues/252) before exposure.
The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns the accepted technical design and environment boundaries; the
[delivery lifecycle](../development/delivery-lifecycle.md#public-production-gates)
separates permission to contract from permission to expose. Best effort does not
waive personal-data protection, account recovery, provider rights or security.

**Owner-approved public account baseline (#274), prepared; public operation pending:** accounts are for adults
(18+) by explicit declaration, without collecting a birth date or identity document;
email is the sign-in identifier and names are not required. Sign-in is to offer an
optional, unchecked "Seguir conectado" (approved target, not implemented; #374); the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#production-realm-contract)
owns its session bounds. The owner, a natural
person **Rubén Hernández López**, is the data controller and authorizes publication of
his legal name and contact `hola@gameometro.com`. The initial project is personal and
non-economic: no ads, affiliation, sponsorship, donations, sales or professional
promotion. On those facts the owner adopts the interpretation that LSSI article 10
domicile/NIF publication does not initially apply; this is not legal certainty and
must be reassessed on an activity change or contrary authoritative criterion.
Verified accounts and ratings are kept until deleted, regardless of inactivity,
unless a legal obligation applies. Never-verified accounts are to be deleted after
30 days from creation; cleanup is prepared locally with a mandatory verified
maintenance fence and is not scheduled on a real environment. Access,
export, rectification and deletion use manual administrative procedures; no privacy
portal is built for launch. The [public legal drafts](../legal/public-texts-es.md) await owner review; the
[data-protection record](../development/data-protection.md) owns operational duties,
the initial legal-blocking schedule, owner-approved A/B/C and the local
implementation/remaining production evidence.
Mailjet Free remains selected for transactional mail; the owner accepts its
professional-use contractual uncertainty without another eligibility inquiry, while
DPA/retention/transfer duties remain. The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery)
owns selected production storage and remaining host evidence.

**Owner decision, 2026-10-17 (#274), superseding preventive suppression:** retain
current IGDB content, including translations, external scores, covers and decorative
images, without advance hiding/disable controls or new feature flags. The IGDB query
remains unanswered; silence is not permission and lawful use is not established. The
owner knowingly accepts that uncertainty and will assess concrete objections,
restrictions or takedown requests when received, including temporarily suspending
public Gameómetro while finding a solution. This acceptance does not discharge
licence/copyright, attribution, termination/deletion or personal-data obligations;
CDN transparency, lawful-basis and transfer questions remain launch requirements.
No further IGDB contact is authorized. The
[#274 research](../research/public-release-rights-and-privacy.md) records evidence,
legal uncertainties and remaining gates.

Commercial sustainability is exploratory. A plausible initial goal is a small
community of real users and modest, non-intrusive monetization that offsets basic
operating costs while preserving a clean, low-friction experience. This is not
validated demand, product–market fit, a revenue target, a committed business roadmap,
or an adopted monetization model. This first launch includes no monetization;
commercial/provider scope and any spend outside the approved ceiling require a
separate explicit owner decision. No service is contracted by this documentation.

Broader vision capabilities must earn inclusion through evidence, value, cost, risk,
and solo operability; GitHub Issues own evaluation and delivery work.
