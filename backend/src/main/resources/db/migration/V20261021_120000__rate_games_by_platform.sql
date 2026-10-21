-- Issue #359 (ADR-0031): a rating may name the platforms its user played on, each with an
-- optional score of its own, while the user still casts exactly one community vote per game.
-- Additive: every existing rating keeps its value and becomes a direct rating without
-- platforms; no platform is inferred.
--
-- value stays the direct score and becomes nullable: it is NULL exactly when the score is the
-- mean of the rating's scored platforms. platform_score_sum and platform_score_count summarize
-- those scores and are written with the platform rows in one transaction, and contribution
-- derives the rating's one vote from them, so every aggregate still reads one row per user.
-- The quotient keeps twenty decimal places; only presentation rounds.
ALTER TABLE ratings.rating
    ALTER COLUMN value DROP NOT NULL,
    ADD COLUMN platform_score_count smallint NOT NULL DEFAULT 0,
    ADD COLUMN platform_score_sum smallint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_rating_platform_scores CHECK (
        platform_score_count BETWEEN 0 AND 50
        AND platform_score_sum BETWEEN platform_score_count AND 10 * platform_score_count),
    ADD CONSTRAINT ck_rating_one_score_source CHECK ((value IS NULL) = (platform_score_count > 0));

ALTER TABLE ratings.rating
    ADD COLUMN contribution numeric(22, 20) NOT NULL GENERATED ALWAYS AS (
        coalesce(
            value::numeric,
            CAST(platform_score_sum AS numeric(23, 20)) / nullif(platform_score_count, 0))
    ) STORED;

-- The game aggregate reads contributions by game; the bare value no longer is one.
CREATE INDEX ix_rating_game_contribution ON ratings.rating (game_id, contribution);
DROP INDEX ratings.ix_rating_game_value;

-- Ratings' own copy of the labels of platforms named by ratings, as genre_label is for genres:
-- reads and deletes never ask Catalogue, and a saved platform keeps its name if it no longer has
-- a current release. Catalogue's taxonomy stays the identity authority.
CREATE TABLE ratings.platform_label (
    platform_id uuid PRIMARY KEY,
    display_name varchar(200) NOT NULL,
    CONSTRAINT fk_platform_label_platform
        FOREIGN KEY (platform_id) REFERENCES catalogue.platform (platform_id),
    CONSTRAINT ck_platform_label_name CHECK (btrim(display_name) <> '')
);

CREATE TABLE ratings.rating_platform (
    user_id uuid NOT NULL,
    game_id uuid NOT NULL,
    platform_id uuid NOT NULL,
    value smallint,
    CONSTRAINT pk_rating_platform PRIMARY KEY (user_id, game_id, platform_id),
    CONSTRAINT fk_rating_platform_rating
        FOREIGN KEY (user_id, game_id) REFERENCES ratings.rating (user_id, game_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_rating_platform_label
        FOREIGN KEY (platform_id) REFERENCES ratings.platform_label (platform_id),
    CONSTRAINT ck_rating_platform_value CHECK (value BETWEEN 1 AND 10)
);

-- Per-platform community readings scan only the scored platforms of one game.
CREATE INDEX ix_rating_platform_game_scores
    ON ratings.rating_platform (game_id, platform_id, value) WHERE value IS NOT NULL;

COMMENT ON TABLE ratings.platform_label IS
    'Labels of the catalogue platforms that ratings name, refreshed when a rating names them.';
COMMENT ON TABLE ratings.rating_platform IS
    'Personal: a platform a product UserId played a rated game on, with that user''s optional score there; removed with the rating.';
COMMENT ON COLUMN ratings.rating.contribution IS
    'The rating''s one community vote: the direct value, else the mean of its scored platforms.';

-- The read contracts keep their columns and now count one contribution per rating; a fractional
-- contribution falls in its half-up integer bucket.
CREATE OR REPLACE VIEW ratings.observability_score_distribution AS
SELECT round(contribution)::smallint AS score, count(*) AS ratings
FROM ratings.rating GROUP BY 1;
CREATE OR REPLACE VIEW ratings.observability_game_rankings AS
SELECT r.game_id AS ordering_key, g.canonical_title AS game, g.slug,
       count(*) AS votes, avg(r.contribution)::double precision AS mean_score
FROM ratings.rating r LEFT JOIN ratings.game_listing g ON g.game_id = r.game_id
GROUP BY r.game_id, g.game_id, g.canonical_title, g.slug;
