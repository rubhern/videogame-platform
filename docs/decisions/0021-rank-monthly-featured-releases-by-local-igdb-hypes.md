# ADR-0021: Rank monthly featured releases by locally stored IGDB Hypes

- **Status:** Accepted
- **Date:** 2026-10-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Issue:** [#151](https://github.com/rubhern/videogame-platform/issues/151)

## Context

Release discovery listed recent and upcoming releases by date only, so it could not
say which releases of a month draw attention. The owner approved a **Destacados** view
that highlights one featured release of a calendar month and up to five more, chosen
automatically by a simple and explainable rule, without editorial curation and without
claiming quality. The owner validated September/October 2026 data and selected **Game.hypes** instead
of Visits: new-launch attention is clearer after excluding old ports and editions.
IGDB defines Hypes as follows received before release ([provider reference](https://api-docs.igdb.com/#game)).

[ADR-0004](0004-synchronize-and-serve-local-catalogue-data.md) and
[ADR-0017](0017-discover-catalogue-members-automatically-from-igdb.md) keep IGDB an
acquisition source only: public requests read PostgreSQL. A ranking therefore needs the
signal stored locally, acquired by a bounded operation, and served from a query whose
work does not grow with the catalogue. The owner's reference also composes the page from
wide artwork, which the catalogue's portrait covers cannot provide without distortion.

## Decision

- **Only product-useful featured evidence.** Store one positive integer attention count
  (or absence), own first-release calendar date (or absence), a distinct accepted-product
  eligibility boolean, source and acquisition time per Game. Never store provider parent
  identifiers, popularity types, provider calculation timestamps or signal history.
- **Acquired with the Game.** The existing bounded Game request now carries Hypes,
  first_release_date and version_parent alongside its media. The adapter maps the date
  at UTC, an edition relationship to a boolean, and zero/missing Hypes to absence.
  The application reuses the import allowlist without widening it. A valid answer replaces
  complete evidence in the Game transaction, including explicit absence. Invalid mapping
  or a failed acquisition/write preserves the last valid state. Ordinary repair refreshes
  this evidence too. No extra popularity request or page-level popularity failure stage
  remains. Evidence does not advance the catalogue revision.
- **Landscape media, selected once.** The featured frames are landscape, so the same run
  also keeps context-selected hero/card images and an optional secondary-card logo per Game, as metadata only under the
  amended [ADR-0001](0001-reference-igdb-cover-images.md). Artworks and screenshots come
  with the per-Game work request the run already makes; logos, which IGDB serves apart
  from games, come from one bounded lookup per provider page, complete or failed as a
  whole. A deterministic metadata policy (FEAT-003, FEAT-004) never offers artwork the
  provider labels as a cover, a logo or an icon. For the hero, which sets the title itself, it
  chooses artwork without a provider-declared title that best fills the wide hero frame, else
  a screenshot, else title artwork; for a card, the least-cropped image; and a transparent logo
  of legible size. Only a newer valid selection replaces the stored one; missing, invalid or
  unavailable media keep it. Reads complete a card's preference with the provider cover shown
  whole, then the product-owned landscape fallback; the hero goes straight to that designed
  fallback and never presents a cover.
- **Ranked in PostgreSQL.** Enforce FEAT-001: an accepted distinct product, known own
  first-release date in the month, positive Hypes, and a Full Release in the represented
  month at day or matching month precision, neither cancelled, delayed nor pending review.
  Remakes/remasters may qualify as new products under the same rule. Rank by Hypes DESC,
  GameId ASC, keep six and present one qualifying release per platform. The existing period
  index bounds the month; primary-key evidence probes and bounded snapshot/media reads keep
  memory proportional to the response. Excluded games remain in normal discovery. Review
  classification remains unchanged; #226 owns its investigation.
- **Remove obsolete Visits state.** A forward migration drops the unused Visits table
  and creates the featured-evidence table. Earlier locally applied migrations remain immutable.
  Existing catalogue and media survive; re-synchronization is required to acquire Hypes.
  Rollback requires the prior schema/backup and application together; Visits cannot be inferred
  from Hypes. This is unpublished #151 data, not a change to an accepted release contract.
- **Served honestly.** The current calendar month in `Europe/Madrid` is the default;
  a visitor may select another month of the current calendar year, which is ranked with
  the same current signals. A month of another year is an invalid filter: historical
  browsing is not part of featured discovery.
  The response states whether the month is ranked, unranked for lack of signals or
  without qualifying releases, and how current the oldest ranked signal is under the
  catalogue freshness policy. The value itself is never exposed, and the selection is
  presented as attention, never as quality or an award. Each item carries its landscape
  context-selected image, whether it may be cropped to fill or must be shown whole, and an optional secondary-card logo when one
  exists.

## Alternatives considered

- **Call IGDB from the featured request:** rejected by ADR-0004; provider latency and
  outages would become product behaviour.
- **A separate scheduled popularity job:** rejected for now. It adds a scheduler and a
  second provider-integration path; the synchronization already visits every Game whose
  releases the operator wants current.
- **Visits acquisition retained for hypothetical use:** rejected; it has no independent
  consumer. Hypes arrives with an existing request.
- **A weighted Gameómetro score from several primitives:** out of scope for #151; the
  owner chose Hypes alone after real-data validation.
- **Popularity history per month:** rejected; nothing needs it yet, and past months are
  honestly labelled as ranked by current attention.
- **Covers cropped or stretched to landscape:** rejected; the owner's reference is built on
  wide artwork, and a crop of a portrait cover loses its title and subject.
- **Every artwork and screenshot stored, chosen per request:** rejected; only the selection
  is needed, and a policy change can wait for the next synchronization.
- **Logos read per Game:** rejected; one lookup per provider page bounds the extra provider
  work instead of adding a request for every Game.
- **Image analysis or subjective scoring:** rejected; dimensions, transparency, animation and
  the provider's image-type labels are enough for a deterministic choice. An undeclared title
  painted into artwork or a screenshot therefore stays possible.

## Consequences

Featured releases are served locally with bounded work, survive provider outages with
the last valid signals and media, and add two small tables (at most one signal and two
media rows per Game) and bounded logo requests per provider page; the per-Game request
only grows by the small featured-evidence and media metadata it returns. Their freshness depends on how often the operator synchronizes the month: until
a run observes a Game, its signal can be stale, and the response says so. The year's
earlier and later months are ranked by today's attention, which suits discovery but is not
a historical record; other years are not served at all. Old ports and editions are excluded by eligibility, not by title lists or a second score.
Incomplete first-release evidence excludes a Game; provider/mapping outages may serve
last-valid stale evidence, whose age remains explicit.

## Evidence

`FeaturedReleaseScalabilityIT` explains the exact serving query over a generated large
catalogue, with full-release candidates, first-release evidence, deliberate Hypes ties and
stored media. The corrected 100k-release query used the period index and primary-key
probes, returned six bounded games and had no sequential scan of releases, games,
featured evidence or media. The measured local execution was about 3.6 ms; this is
point-in-time plan evidence, not a latency gate. Monthly result evidence and the named
exclusion audit belong to #151 rather than an evergreen decision record.

## Reconsider when

Revisit when observed monthly selections are repeatedly unconvincing (unusual traffic,
systematic disadvantage of platform exclusives, missing upcoming hits), when a second
real use of popularity appears, when history or trend questions become product needs,
when recurring automatic synchronization is approved, when a month's candidates grow
to tens of thousands (the signal is probed once per candidate), when IGDB documents or
changes the image-type labels the media policy reads (title key art, covers, logos and
icons are observed, undocumented labels), or when IGDB changes Hypes' meaning
or terms. Any multi-signal score needs its own decision.
