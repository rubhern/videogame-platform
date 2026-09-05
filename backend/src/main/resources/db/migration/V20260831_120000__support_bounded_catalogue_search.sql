-- Adds the bounded UC-002 catalogue-search read model.
--
-- Search must stay in PostgreSQL: normalized text and its `simple` text-search
-- vector are stored generated columns so filtering, ranking, counting and paging
-- never materialize the publication in Java. `simple` carries no stemmer and no
-- stop-word list, which keeps matching non-fuzzy as the reviewed contract requires.

-- Product normalization rule for searchable catalogue text. It is IMMUTABLE because
-- every step is: NFD decomposition, removal of the Unicode combining diacritical
-- marks, lowercasing, removal of apostrophe-like separators so `Marvel's` and
-- `Marvels` share one form, and folding of every remaining non-alphanumeric run into
-- a single space. Marks are removed before lowercasing so accented capitals fold
-- correctly even where `lower()` only covers ASCII. Letters carrying a stroke rather
-- than a combining mark (o-slash, l-stroke) are not diacritics and stay unfolded.
-- CatalogueSearchText applies the identical rule in Java; the persistence integration
-- test asserts both agree.
CREATE FUNCTION catalogue.normalize_search_text(source text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
RETURN btrim(
    regexp_replace(
        regexp_replace(
            lower(
                regexp_replace(
                    normalize(source, NFD),
                    '[\u0300-\u036f]',
                    '',
                    'g'
                )
            ),
            '[\u0027\u2019\u02bc\u00b4\u0060]',
            '',
            'g'
        ),
        '[^[:alnum:]]+',
        ' ',
        'g'
    )
);

COMMENT ON FUNCTION catalogue.normalize_search_text(text) IS
    'Case- and diacritic-insensitive normalization for catalogue search; display titles are never rewritten.';

ALTER TABLE catalogue.game_snapshot
    ADD COLUMN normalized_title text GENERATED ALWAYS AS (
        catalogue.normalize_search_text(canonical_title)
    ) STORED,
    ADD COLUMN title_search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple'::regconfig, catalogue.normalize_search_text(canonical_title))
    ) STORED;

-- The vector alone is the selective column. Measured plans show that adding the
-- non-selective publication_id to the GIN posting lists makes the index far more
-- expensive than the cheap publication recheck it would save, so the publication is
-- filtered after the index scan.
CREATE INDEX ix_game_snapshot_title_search
    ON catalogue.game_snapshot
    USING gin (title_search_vector);

-- Alias membership belongs to a publication exactly like the game snapshot, so a
-- published search reads one coherent snapshot. GAME-004 keeps an alias resolving to
-- one game, and only an approved alias is searchable.
CREATE TABLE catalogue.game_alias (
    publication_id uuid NOT NULL,
    game_id uuid NOT NULL,
    alias varchar(300) NOT NULL,
    normalized_alias text GENERATED ALWAYS AS (
        catalogue.normalize_search_text(alias)
    ) STORED,
    alias_search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple'::regconfig, catalogue.normalize_search_text(alias))
    ) STORED,
    alias_kind varchar(32) NOT NULL,
    language_tag varchar(35),
    approval_status varchar(32) NOT NULL,
    source_kind varchar(32) NOT NULL,
    source_name varchar(200) NOT NULL,
    PRIMARY KEY (publication_id, game_id, alias),
    CONSTRAINT fk_game_alias_game_snapshot
        FOREIGN KEY (publication_id, game_id)
        REFERENCES catalogue.game_snapshot (publication_id, game_id),
    CONSTRAINT ck_game_alias_not_blank
        CHECK (btrim(alias) <> ''),
    CONSTRAINT ck_game_alias_searchable
        CHECK (catalogue.normalize_search_text(alias) <> ''),
    CONSTRAINT ck_game_alias_kind
        CHECK (alias_kind IN ('localized', 'alternative', 'historical', 'product_curated')),
    CONSTRAINT ck_game_alias_language_tag
        CHECK (language_tag IS NULL OR language_tag ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CONSTRAINT ck_game_alias_approval_status
        CHECK (approval_status IN ('approved', 'pending', 'rejected')),
    CONSTRAINT ck_game_alias_source_kind
        CHECK (source_kind IN ('external_provider', 'product_curated', 'official_source')),
    CONSTRAINT ck_game_alias_source_name_not_blank
        CHECK (btrim(source_name) <> '')
);

COMMENT ON TABLE catalogue.game_alias IS
    'Published alternative titles resolving to one game; never a product identity.';

-- GAME-004: one normalized alias resolves to a single game inside a publication.
CREATE UNIQUE INDEX uq_game_alias_normalized
    ON catalogue.game_alias (publication_id, normalized_alias);

-- Only an approved alias is ever searchable, so the partial index is both the
-- correct scope and the smaller one.
CREATE INDEX ix_game_alias_search
    ON catalogue.game_alias
    USING gin (alias_search_vector)
    WHERE approval_status = 'approved';

-- Bounded per-game release context for a search result page.
CREATE INDEX ix_release_snapshot_publication_game_period
    ON catalogue.release_snapshot (publication_id, game_id, period_start, release_id);
