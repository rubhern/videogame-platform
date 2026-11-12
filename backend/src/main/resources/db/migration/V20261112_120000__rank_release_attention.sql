-- Issue #458: release attention ranks by IGDB Hypes, Want to Play and Visits, with a derived
-- product's parent signals, plus an operator-loaded editorial anticipation list (ADR-0041).
-- Expand-only: new tables and indexes that older application versions never read.

-- The provider's Want to Play and Visits primitives of a Game, and the three signals of the
-- product a derived Game (remake, remaster, port, expanded game) comes from. Hypes stay in
-- game_featured_evidence. A primitive is the provider's share of its total, never a count, so it is
-- stored as reported. A missing signal is NULL and never zero; a Game without any signal has no row.
CREATE TABLE catalogue.game_attention_evidence (
    game_id uuid PRIMARY KEY REFERENCES catalogue.game (game_id),
    want_to_play double precision,
    visits double precision,
    parent_hypes bigint,
    parent_want_to_play double precision,
    parent_visits double precision,
    source_name varchar(100) NOT NULL CHECK (btrim(source_name) <> ''),
    observed_at timestamptz NOT NULL,
    CONSTRAINT ck_attention_want_to_play CHECK (want_to_play IS NULL OR (want_to_play > 0 AND want_to_play <= 1)),
    CONSTRAINT ck_attention_visits CHECK (visits IS NULL OR (visits > 0 AND visits <= 1)),
    CONSTRAINT ck_attention_parent_hypes CHECK (parent_hypes IS NULL OR parent_hypes > 0),
    CONSTRAINT ck_attention_parent_want_to_play CHECK (
        parent_want_to_play IS NULL OR (parent_want_to_play > 0 AND parent_want_to_play <= 1)),
    CONSTRAINT ck_attention_parent_visits CHECK (
        parent_visits IS NULL OR (parent_visits > 0 AND parent_visits <= 1))
);

COMMENT ON TABLE catalogue.game_attention_evidence IS
    'Current IGDB Want to Play and Visits shares of a Game and its parent product''s signals; ranks release attention with Hypes.';

-- One operator-loaded editorial anticipation list per date range. Ranges never overlap, so every
-- day belongs to at most one list; loading the same range again replaces its list.
CREATE TABLE catalogue.editorial_attention_list (
    list_id uuid PRIMARY KEY,
    range_from date NOT NULL,
    range_to date NOT NULL,
    prompt_version varchar(64) NOT NULL CHECK (btrim(prompt_version) <> ''),
    generator_model varchar(100) CHECK (generator_model IS NULL OR btrim(generator_model) <> ''),
    generated_at timestamptz,
    loaded_at timestamptz NOT NULL,
    CONSTRAINT ck_editorial_list_range CHECK (range_to >= range_from AND range_to - range_from < 92),
    CONSTRAINT ex_editorial_list_range EXCLUDE USING gist (
        daterange(range_from, range_to, '[]') WITH &&)
);

CREATE TABLE catalogue.editorial_attention_entry (
    list_id uuid NOT NULL REFERENCES catalogue.editorial_attention_list (list_id) ON DELETE CASCADE,
    game_id uuid NOT NULL REFERENCES catalogue.game (game_id),
    entry_rank smallint NOT NULL CHECK (entry_rank BETWEEN 1 AND 50),
    score smallint NOT NULL CHECK (score BETWEEN 1 AND 100),
    file_title varchar(200) NOT NULL CHECK (btrim(file_title) <> ''),
    rationale varchar(500),
    sources text[] NOT NULL DEFAULT '{}' CHECK (cardinality(sources) <= 10),
    PRIMARY KEY (list_id, game_id),
    CONSTRAINT uq_editorial_entry_rank UNIQUE (list_id, entry_rank)
);

-- The ranking probes a candidate Game's entries; the range test then reads at most one list per day.
CREATE INDEX ix_editorial_entry_game ON catalogue.editorial_attention_entry (game_id);

COMMENT ON TABLE catalogue.editorial_attention_list IS
    'Operator-loaded editorial anticipation lists; each covers one date range and ranks release attention.';
COMMENT ON TABLE catalogue.editorial_attention_entry IS
    'One catalogued Game of an editorial anticipation list with its 1-100 anticipation score.';

-- An editorial file names a Game by its IGDB page slug or by title. Both lookups are by key, so
-- matching up to fifty entries never scans the catalogue.
CREATE INDEX ix_game_snapshot_attribution ON catalogue.game_snapshot (cover_source_url);
CREATE INDEX ix_game_snapshot_normalized_title ON catalogue.game_snapshot (normalized_title);

COMMENT ON TABLE catalogue.game_featured_evidence IS
    'Current normalized first-release, distinct accepted product and Hypes evidence; with game_attention_evidence and editorial lists it ranks release attention.';
