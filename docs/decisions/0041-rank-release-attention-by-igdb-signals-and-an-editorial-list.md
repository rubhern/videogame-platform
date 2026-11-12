# ADR-0041: Rank release attention by IGDB signals and an editorial list

- **Status:** Proposed
- **Date:** 2026-11-12
- **Owner:** Ruben Hernandez
- **Scope:** Release discovery ordering: Destacados, Explorar's `hype` order, the calendar's chosen-day preview and Próximos destacados
- **Issue:** [#458](https://github.com/rubhern/videogame-platform/issues/458)
- **Related:** [ADR-0004](0004-synchronize-and-serve-local-catalogue-data.md), [ADR-0017](0017-discover-catalogue-members-automatically-from-igdb.md), [ADR-0021](0021-rank-monthly-featured-releases-by-local-igdb-hypes.md), [ADR-0028](0028-authorize-catalogue-operators-with-a-bff-client-role.md)

## Context

[ADR-0021](0021-rank-monthly-featured-releases-by-local-igdb-hypes.md) ranks featured releases
by IGDB Hypes alone, and #457 made Destacados the head of Explorar's Hype order. The owner
compared the November 2026 top 15 with a web and press consensus of the month's most
anticipated games: Hypes placed 5 of the 14 reachable reference games in its top 15.

Phase 1 of #458 read the real provider data for November and October 2026 with owner approval
and compared the approaches below. Two findings shaped the decision:

- **IGDB has more anticipation evidence than Hypes.** `popularity_primitives` exposes Want to
  Play and Visits for most candidates. Their values are shares of a provider total, never counts.
  Steam Top Sellers is almost constant, Steam wishlists are sparse and Twitch hours are noisy, so
  none of those helps a monthly ranking.
- **Some anticipated games are invisible to IGDB.** A new entry of an annual series, a medium
  or indie launch, or a port that IGDB records without signals never reaches the top on provider
  evidence alone, whatever the formula. Only an editorial source closes that gap.

The owner rejected two variants after seeing their lists: percentile scaling, because it
compressed every score into a narrow band, and English Wikipedia page views, because they added
noise (a port inherited its parent's article) without improving the overlap.

## Decision

Release attention replaces Hypes wherever release discovery orders by attention. The policy is
versioned as `attention-v1` and defined once, in `ReleaseAttentionPolicy` and the SQL that
`ReleaseAttentionSql` shares, by the rules `ATT-001` to `ATT-006` of the
[domain model](../architecture/domain/mvp-domain-model.md#canonical-invariant-register).

- **Two blocks of equal weight.** The provider block is the mean of Hypes, Want to Play and
  Visits, each scaled as `ln(1 + x) / ln(1 + max)` over the candidates of the same range and
  filters. The editorial block is the 1–100 score the game holds in the editorial list covering
  the range, or zero. A log scale keeps the spread the owner asked for without letting one
  blockbuster flatten every other game.
- **Only launches (owner decision, 2026-11-12).** A release draws attention only when its stage
  is a full release or unknown; early access, advance access, betas and alphas never do. This
  reverses the part of ADR-0021's 2026-11-06 amendment that let an early-access launch feature:
  Forza Horizon 6's premium advance access (15 May 2026) and its launch (19 May) could otherwise
  lead two consecutive weeks. An unknown stage counts because most ordinary launches carry none.
  Explorar still lists every stage, after the games with attention, and the highlights calendar
  neither counts nor colours a non-launch release. An early-access game reaches Destacados when
  its full release arrives.
- **Derived products and re-releases.** A remake, remaster, port or expanded game takes, per
  signal, the greater of its own value and half of its parent product's. A game with a launch
  release, neither cancelled nor delayed, that ended before the range keeps half of its own
  signals instead. The rule reads the catalogue's own releases, which hold every release of each
  game, so an earlier early access never turns a 1.0 into a re-release. This keeps an old title's
  history from promoting its new platform release, while a port without signals of its own still
  reflects its parent.
- **Missing is not zero.** A missing signal contributes nothing and is stored as absent. A game
  with no signal and no editorial score has no attention: Explorar lists it after every game
  with attention, and Destacados and Próximos destacados never rank it.
- **Acquired in the existing synchronization.** Each provider page adds one bounded lookup: one
  Game request for the game types and parents' Hypes, then the Want to Play and Visits
  primitives of up to 500 Games and their parents, 200 Games per request. Values are stored as
  the provider's shares in `catalogue.game_attention_evidence`. A failed lookup keeps every last
  valid signal and never fails a Game. Repair does not refresh these signals.
- **Editorial anticipation lists, loaded by the operator.** The admin panel shows a versioned
  prompt (`editorial-anticipation@2`) for a language model with web access. The model delivers
  a downloadable JSON file holding a date range of at most 92 days and up to 50 games scored 1–100, each
  with its IGDB slug, release date, rationale and sources. The panel previews the file against
  the catalogue: by IGDB page slug first, else by an exact title that only one game carries. It
  warns about unmatched, ambiguous, out-of-range, date-conflicting and duplicate entries, and lets
  the operator assign or exclude any entry before loading. Loading the same range again replaces
  its list, which is how a correction is made. Overlapping ranges are refused, and PostgreSQL
  guarantees it. The prompt and the file schema change together under a new prompt version.
- **One ordering everywhere, for every attention view.** Destacados remains the head of Explorar's order for the same
  period and platforms (#457). The calendar's chosen-day preview is Explorar's head for that
  day. Próximos destacados ranks its unchanged candidates over its horizon, which starts on the
  evaluation date, so a game already released elsewhere counts as a re-release there. The calendar's day
  counts and density colours keep the Hypes threshold and sums of `UC-016`, because they count
  and colour days rather than order games, but they count launches only. Próximos destacados
  accepts a launch of unknown stage as well as a full release.
- **Presentation.** The sort keeps its wire value `hype`, so existing links keep working. Its
  label becomes **Interés**, and missing-signal notices read **Sin datos de interés**. The value
  is never exposed.

## Amendment 2026-11-12 (#450)

[ADR-0042](0042-manage-approved-product-settings-in-postgresql.md) makes the weights of `attention-v1` operator product settings: the relative weights of
Hypes, Want to Play and Visits inside the provider block, the editorial block's share of the
attention, the part of a parent's signal a derived product borrows and the part of its own signals a
re-release keeps. The values decided above are the defaults. The shape of the formula, the log
scale, the launch rule and the editorial list are unchanged, so a tuned weight keeps the version
`attention-v1`; a new signal or scale is still a new version.

## Alternatives considered

- **Keep Hypes alone.** It placed 5 of 14 reference games in November's top 15 and missed
  obvious launches.
- **The provider block alone.** At 7–8 of 14, it was better but could not reach games without
  IGDB evidence.
- **Percentile scaling of the provider signals.** The overlap matched the log scale, but the
  scores fell into a narrow band, which the owner rejected.
- **English Wikipedia page views as a third block.** It added a second external provider, broke
  on shared parent articles (a port of a 2002 game reached the top 15), and dropped the overlap
  from 13 to 12.
- **Editorial picks typed by the owner.** It would need manual research every month, which the
  owner explicitly declined. The prompt delegates the research, and the preview keeps the
  operator in control.
- **A model called by the application.** It would add a paid or rate-limited dependency, a
  secret and non-deterministic behaviour to the runtime. Loading a reviewed file keeps every
  read local, reproducible and free.

## Consequences

- Equivalent lists share one deterministic order. Attention is rounded to four decimals, ties
  follow Explorar's date order, and the unique game identity ends the order.
- The provider block is relative to the candidate set. Narrowing the platforms or the range can
  reorder two games whose signals differ in kind, while Destacados and Explorar stay identical
  for the same filters.
- The editorial block is only as good as the file. Phase-1 evidence included a wrong slug, two
  bundles the catalogue does not import and two release dates that disagree with IGDB. The
  preview surfaces each of these, but loading a list remains an operator judgement.
- Ranges without a list rank by the provider block alone, as before, with the new signals.
- Signals are current, not historical. Past periods are ranked by today's evidence, as
  ADR-0021 already accepted.
- Rollback is a code revert of the ordering expression. Hypes stay stored, and the new tables
  are additive.

## Evidence

Phase 1 of #458 compared, for November 2026, how many of the 14 reference games reachable in
the catalogue each approach placed in its top 15:

| Approach | Overlap |
|---|---|
| Hypes | 5 |
| Provider block | 7–8 |
| Provider block + Wikipedia | 11 |
| Provider block + Wikipedia + editorial | 12 |
| Provider block + editorial | 13 |

October 2026 was repeated with a model-generated editorial file of 28 entries: 14 of the file's
top 15 reached the combined top 15. The November editorial list was simulated from the same
press lists as the reference, so that figure is optimistic. Both months were read in November,
so their anticipation was measured after the fact. These are point-in-time evidence, not gates.

## Reconsider when

- The owner reports repeatedly unconvincing selections.
- IGDB changes the meaning, scale or availability of the popularity primitives.
- A list's games are often unmatched.
- An operator needs history, or several lists for one day.
- A second product use needs a distinct pre-release or general-attention policy.
- A range's candidates grow to tens of thousands, because the scale reads every candidate once.
