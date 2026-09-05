# ADR-0016: Search the bounded catalogue with PostgreSQL text search

- **Status:** Accepted
- **Date:** 2026-08-31
- **Owner:** Ruben Hernandez
- **Scope:** UC-002 bounded catalogue search reads
- **Issue:** [#28](https://github.com/rubhern/videogame-platform/issues/28)

## Context

`UC-002` must find a curated game by canonical title or approved alias, case- and
diacritic-insensitively, requiring every query token and without fuzzy matching. It
must never call a provider, must keep several matching games separate, and must order
and page deterministically. Aliases did not exist in the schema, and the catalogue had
no comparable form of its display titles.

The naive implementations are both wrong for this project. Matching in Java requires
loading the publication; matching with `LIKE '%token%'` cannot use an index and grows
with the table.

## Decision

- Store the comparable form, never rewrite the display title. `game_snapshot` and the
  new publication-scoped `game_alias` carry stored generated columns holding the
  normalized text and its `tsvector`.
- Own normalization as one product rule expressed twice, in the same words: the
  `catalogue.normalize_search_text` SQL function and the `CatalogueSearchText` domain
  value object. NFD decomposition, removal of the Unicode combining marks,
  simple per-code-point lowercasing (without contextual substitutions such as final
  Greek sigma), removal of apostrophe-like separators, and folding of every other
  non-alphanumeric run into one space. A persistence integration test asserts the two
  agree; letters written with a stroke are not diacritics and stay unfolded.
- Use the `simple` text-search configuration. It has no stemmer and no stop-word list,
  which is exactly what "non-fuzzy" and "requires every token" mean here. All-token
  matching is one `tsquery` of `token:*` terms combined with `&`, so a partial query
  matches a word prefix and never an infix.
- Use an application-owned `GameSearchReadPort` with an explicit PostgreSQL/JDBC
  adapter, in one read-only `REPEATABLE READ` transaction against the sole current
  publication, exactly like ADR-0015.
- Produce candidates as two index-backed branches — the canonical title and the
  approved aliases — then reduce them to one row per game. A game matched by several
  aliases is never duplicated, and two games matching one query always stay separate
  results.
- Rank canonical matches before alias-only matches, and exact before prefix before
  plain all-token, then order by normalized canonical title and finally by the unique
  `gameId` of a one-row-per-game result.
- Expose `matchedAlias` only when an approved alias justified the match, choosing the
  best-ranked matching alias deterministically. Keep the lexical minimum for each
  alias rank and choose the first non-null rank; do not accumulate every matching
  alias in an array just to retain the first one.
- Bound the release context per result explicitly and join it only after
  `LIMIT`/`OFFSET`, so rows crossing into Java and application memory are
  `O(pageSize x releaseContextLimit)`. PostgreSQL still processes matching candidates
  for ranking and exact counts, and deeper offsets require more database work.
- Only an approved alias is searchable, and the searchable-alias GIN index is partial
  on that condition.
- Keep the raw query out of storage, logs and metric labels. Search telemetry is one
  counter over the closed outcome vocabulary `zero_results` and `results`.

## Alternatives considered

- **`ILIKE '%token%'`:** rejected because a leading wildcard cannot use an index, so
  cost grows with the table.
- **`pg_trgm` similarity:** rejected because the approved contract is explicitly
  non-fuzzy; it would invent matches the product does not promise.
- **`unaccent`:** rejected because it is only `STABLE`, so using it in a generated
  column or index requires declaring a wrapper `IMMUTABLE` that is not. Built-in
  `normalize(..., NFD)` plus mark removal is genuinely immutable.
- **A language-specific text-search configuration:** rejected because stemming and
  stop words would both drop tokens and match words the visitor did not write.
- **A multicolumn GIN over `(publication_id, search_vector)`:** rejected on measured
  evidence, see below.
- **Elasticsearch or another search service:** rejected without a measured PostgreSQL
  limitation.
- **A denormalized search table:** deferred while measured normalized queries meet the
  access path.

## Consequences

Matching, ranking, counting and pagination stay in PostgreSQL; response size and
application memory are bounded, and ordering is deterministic and stateless.
Search adds two GIN indexes and two stored generated columns per searchable
row, so the publication costs more storage and slightly more write work during
synchronization. Curation gains a real obligation: an alias is only searchable once
approved.

Because the alias table is publication-scoped, aliases are republished with their
publication exactly like game snapshots, and retained publications accumulate
searchable rows.

## Evidence and reconsideration triggers

`scripts/analyze-catalogue-search.sh` provides opt-in representative data and
production query plans through `CatalogueSearchScalabilityIT`. The test uses the same
`GameSearchSql` count/page statements and parameters as the adapter and writes the
complete `EXPLAIN (ANALYZE, BUFFERS)` JSON to ignored `backend/target/query-plans/`.
The smaller supported fixture checks correctness and response bounds while allowing
PostgreSQL to choose a sequential scan; representative-scale assertions check the
indexed search/count and bounded release-context paths without imposing latency
thresholds. The accepted local run before the refactor
used 100,000 games, 100,000 aliases and 500,000 releases. A query matching 1,000 games
and 858 approved aliases counted in about 33 ms and returned its page of 20 in about
19 ms, using `ix_game_snapshot_title_search`, `ix_game_alias_search` and
`ix_release_snapshot_publication_game_period`, with no sequential scan on any
catalogue table and 60 rows crossing into Java. These observations are historical
evidence, not portable latency gates.

Adding `publication_id` to the GIN indexes was measured and rejected: at 100,000
games the multicolumn index scan cost about 2,096 and read 512 buffers where the
vector-only index cost about 35 and read 4, because the publication is not selective.
The publication is therefore filtered after the index scan, and PostgreSQL is free to
ignore an index that would not help.

Revisit publication retention when the accumulated searchable rows make the
post-index publication recheck material; ranking weights or `ts_rank` when result
ordering is measured to be unhelpful rather than merely simple; a trigram or fuzzy
index only if the product approves fuzzy matching; and a separate search store only
for a measured PostgreSQL limitation.
