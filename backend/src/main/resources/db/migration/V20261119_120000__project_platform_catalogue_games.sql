-- The active range is evaluated using the same Madrid date as UC-019. Storing the
-- breakpoints keeps releases correct at midnight even if no synchronization runs.
CREATE TABLE catalogue.platform_game_presentation (
    publication_id uuid NOT NULL,
    platform_id uuid NOT NULL,
    game_id uuid NOT NULL,
    valid_on daterange NOT NULL,
    release_id uuid NOT NULL,
    occurrence smallint NOT NULL CHECK (occurrence BETWEEN 0 AND 2),
    precedence smallint[] NOT NULL,
    region_code text NOT NULL,
    period_end date,
    precision_rank smallint NOT NULL,
    known_year integer,
    normalized_title text NOT NULL,
    genre_ids uuid[] NOT NULL,
    community_mean numeric(3, 1),
    community_rating_count integer,
    player_score numeric,
    player_score_count integer,
    PRIMARY KEY (game_id, publication_id, platform_id, valid_on WITHOUT OVERLAPS),
    FOREIGN KEY (publication_id, game_id)
        REFERENCES catalogue.game_snapshot (publication_id, game_id) ON DELETE CASCADE
);

-- A revision serializes all refreshes of a game even under snapshot isolation.
-- It owns no catalogue data and has no role on the read path.
CREATE TABLE catalogue.platform_game_presentation_revision (
    game_id uuid PRIMARY KEY REFERENCES catalogue.game (game_id) ON DELETE CASCADE,
    revision boolean NOT NULL DEFAULT false
);

CREATE FUNCTION catalogue.platform_game_presentations(game_ids uuid[])
RETURNS SETOF catalogue.platform_game_presentation
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    -- Static precedence follows ReleasePresentationOrder. The best rank among
    -- already occurred releases can only improve as more dates come into effect.
    WITH source_releases AS (
        SELECT * FROM catalogue.release_snapshot WHERE game_ids IS NULL
        UNION ALL
        SELECT * FROM catalogue.release_snapshot WHERE game_id = ANY(game_ids)
    ), candidates AS (
        SELECT rs.publication_id, rs.platform_id, rs.game_id, rs.release_id,
               rs.period_end, extract(year FROM rs.period_start)::integer AS known_year,
               region.code AS region_code,
               ARRAY[
                   CASE rs.release_status WHEN 'delayed' THEN 1 ELSE 0 END,
                   CASE rs.release_stage WHEN 'full_release' THEN 0 ELSE 1 END,
                   CASE rs.review_status WHEN 'required' THEN 1 ELSE 0 END,
                   CASE rs.verification_level WHEN 'verified' THEN 0 ELSE 1 END,
                   CASE rs.date_precision WHEN 'day' THEN 1 WHEN 'month' THEN 2
                       WHEN 'quarter' THEN 3 WHEN 'year' THEN 4 ELSE 5 END,
                   CASE region.code WHEN 'worldwide' THEN 0 WHEN 'unknown' THEN 2 ELSE 1 END
               ]::smallint[] AS precedence,
               CASE
                   WHEN rs.release_status = 'delayed' THEN NULL
                   WHEN rs.date_precision = 'day' THEN rs.exact_date
                   WHEN rs.date_precision <> 'unknown' THEN rs.period_end + 1
                   WHEN rs.release_status = 'released' THEN '-infinity'::date
               END AS occurs_on
        FROM source_releases rs
        JOIN catalogue.region region ON region.region_id = rs.region_id
        WHERE rs.release_status <> 'cancelled'
    ), ranked AS MATERIALIZED (
        SELECT *, row_number() OVER (
            PARTITION BY publication_id, platform_id, game_id
            ORDER BY precedence, period_end, region_code, release_id
        ) AS rank
        FROM candidates
    ), initial AS (
        SELECT DISTINCT ON (publication_id, platform_id, game_id)
               publication_id, platform_id, game_id, '-infinity'::date AS starts_on,
               rank, (CASE WHEN occurs_on IS NULL THEN 2 ELSE 1 END)::smallint AS occurrence
        FROM ranked
        ORDER BY publication_id, platform_id, game_id,
                 CASE WHEN occurs_on = '-infinity' THEN 0 WHEN occurs_on IS NULL THEN 2 ELSE 1 END,
                 rank
    ), occurrences AS (
        SELECT publication_id, platform_id, game_id, occurs_on AS starts_on, min(rank) AS rank
        FROM ranked
        WHERE occurs_on IS NOT NULL
        GROUP BY publication_id, platform_id, game_id, occurs_on
    ), winners AS (
        SELECT publication_id, platform_id, game_id, starts_on,
               min(rank) OVER (
                   PARTITION BY publication_id, platform_id, game_id ORDER BY starts_on
                   ROWS UNBOUNDED PRECEDING
               ) AS rank,
               0::smallint AS occurrence
        FROM occurrences
        UNION ALL
        SELECT i.*
        FROM initial i
        JOIN ranked r USING (publication_id, platform_id, game_id, rank)
        WHERE r.occurs_on IS DISTINCT FROM '-infinity'::date
    ), changes AS (
        SELECT *, lag(rank) OVER timeline AS previous_rank,
               lag(occurrence) OVER timeline AS previous_occurrence
        FROM winners
        WINDOW timeline AS (PARTITION BY publication_id, platform_id, game_id ORDER BY starts_on)
    ), intervals AS (
        SELECT *, lead(starts_on) OVER (
            PARTITION BY publication_id, platform_id, game_id ORDER BY starts_on
        ) AS ends_on
        FROM changes
        WHERE (rank, occurrence) IS DISTINCT FROM (previous_rank, previous_occurrence)
    )
    SELECT p.publication_id, p.platform_id, p.game_id,
           daterange(nullif(p.starts_on, '-infinity'::date), p.ends_on, '[)'),
           r.release_id, p.occurrence, r.precedence, r.region_code,
           r.period_end, r.precedence[5], r.known_year, gs.normalized_title,
           ARRAY(SELECT gg.genre_id FROM catalogue.game_genre gg
                 WHERE gg.game_id = p.game_id ORDER BY gg.genre_id),
           cs.mean, cs.rating_count, round(es.score / 10, 1), es.score_count
    FROM intervals p
    JOIN ranked r USING (publication_id, platform_id, game_id, rank)
    JOIN catalogue.game_snapshot gs USING (publication_id, game_id)
    LEFT JOIN catalogue.game_community_score cs ON cs.game_id = p.game_id
    LEFT JOIN catalogue.game_external_score es
        ON es.game_id = p.game_id AND es.source_name = 'IGDB'
       AND es.score_kind = 'player' AND es.score IS NOT NULL
$$;

-- The trigger scope is restricted to changed games. Bulk source writes pass bounded
-- batches so the same path can also maintain the projection during operator repairs.
CREATE INDEX ix_release_snapshot_presentation_game
    ON catalogue.release_snapshot (game_id);

CREATE FUNCTION catalogue.refresh_platform_game_presentations(game_ids uuid[])
RETURNS void LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    INSERT INTO catalogue.platform_game_presentation_revision AS stored (game_id)
        SELECT game_id FROM catalogue.game WHERE game_id = ANY(game_ids) ORDER BY game_id
        ON CONFLICT (game_id) DO UPDATE SET revision = NOT stored.revision;

    DELETE FROM catalogue.platform_game_presentation WHERE game_id = ANY(game_ids);
    INSERT INTO catalogue.platform_game_presentation
        SELECT * FROM catalogue.platform_game_presentations(game_ids);
END;
$$;

-- Fence writes before scanning the sources, including writers using the previous
-- application version. Flyway runs this migration transactionally.
LOCK TABLE catalogue.game_snapshot, catalogue.region,
    catalogue.release_snapshot, catalogue.game_genre, catalogue.game_community_score,
    catalogue.game_external_score IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO catalogue.platform_game_presentation
    SELECT * FROM catalogue.platform_game_presentations(NULL);

CREATE INDEX ix_platform_game_presented_latest ON catalogue.platform_game_presentation
    (publication_id, platform_id, period_end DESC NULLS LAST, precision_rank, normalized_title, game_id);
CREATE INDEX ix_platform_game_presented_earliest ON catalogue.platform_game_presentation
    (publication_id, platform_id, period_end, precision_rank, normalized_title, game_id);
CREATE INDEX ix_platform_game_presented_title_asc ON catalogue.platform_game_presentation
    (publication_id, platform_id, normalized_title, game_id);
CREATE INDEX ix_platform_game_presented_title_desc ON catalogue.platform_game_presentation
    (publication_id, platform_id, normalized_title DESC, game_id);
CREATE INDEX ix_platform_game_presented_community ON catalogue.platform_game_presentation
    (publication_id, platform_id, community_mean DESC NULLS LAST, community_rating_count DESC NULLS LAST,
     period_end DESC NULLS LAST, precision_rank, normalized_title, game_id);
CREATE INDEX ix_platform_game_presented_player ON catalogue.platform_game_presentation
    (publication_id, platform_id, player_score DESC NULLS LAST, player_score_count DESC NULLS LAST,
     period_end DESC NULLS LAST, precision_rank, normalized_title, game_id);
CREATE INDEX ix_platform_game_presented_genres ON catalogue.platform_game_presentation
    USING gin (genre_ids);

CREATE FUNCTION catalogue.refresh_platform_game_presentations_after_write()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    affected_sql text;
    batch uuid[];
BEGIN
    IF TG_TABLE_NAME = 'game_snapshot' THEN
        -- Release snapshots reference an existing game snapshot. Only title edits
        -- can invalidate the projection without also writing a release snapshot.
        affected_sql := '
            SELECT n.game_id FROM new_rows n JOIN old_rows o USING (publication_id, game_id)
            WHERE n.normalized_title IS DISTINCT FROM o.normalized_title';
    ELSIF TG_TABLE_NAME = 'region' THEN
        affected_sql := '
            SELECT DISTINCT rs.game_id FROM new_rows n JOIN old_rows o USING (region_id)
            JOIN catalogue.release_snapshot rs USING (region_id)
            WHERE n.code IS DISTINCT FROM o.code';
    ELSIF TG_OP = 'INSERT' THEN
        affected_sql := 'SELECT DISTINCT game_id FROM new_rows';
    ELSIF TG_OP = 'DELETE' THEN
        affected_sql := 'SELECT DISTINCT game_id FROM old_rows';
    ELSE
        affected_sql := 'SELECT game_id FROM old_rows UNION SELECT game_id FROM new_rows';
    END IF;

    FOR batch IN EXECUTE
        'SELECT array_agg(game_id ORDER BY game_id) FROM (
             SELECT game_id, (row_number() OVER (ORDER BY game_id) - 1) / 256 AS batch
             FROM (' || affected_sql || ') affected
         ) numbered GROUP BY batch ORDER BY batch'
    LOOP
        PERFORM catalogue.refresh_platform_game_presentations(batch);
    END LOOP;
    RETURN NULL;
END;
$$;

DO $$
DECLARE
    source text;
BEGIN
    FOREACH source IN ARRAY ARRAY[
        'release_snapshot', 'game_genre', 'game_community_score', 'game_external_score'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER refresh_platform_presentation_insert AFTER INSERT ON catalogue.%I
             REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT
             EXECUTE FUNCTION catalogue.refresh_platform_game_presentations_after_write()', source);
        EXECUTE format(
            'CREATE TRIGGER refresh_platform_presentation_delete AFTER DELETE ON catalogue.%I
             REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT
             EXECUTE FUNCTION catalogue.refresh_platform_game_presentations_after_write()', source);
        EXECUTE format(
            'CREATE TRIGGER refresh_platform_presentation_update AFTER UPDATE ON catalogue.%I
             REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT
             EXECUTE FUNCTION catalogue.refresh_platform_game_presentations_after_write()', source);
    END LOOP;
END;
$$;

CREATE TRIGGER refresh_platform_presentation_title AFTER UPDATE ON catalogue.game_snapshot
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT
    EXECUTE FUNCTION catalogue.refresh_platform_game_presentations_after_write();

CREATE TRIGGER refresh_platform_presentation_region AFTER UPDATE ON catalogue.region
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT
    EXECUTE FUNCTION catalogue.refresh_platform_game_presentations_after_write();

ANALYZE catalogue.platform_game_presentation;
