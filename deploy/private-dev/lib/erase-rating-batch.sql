-- Operational erasure adapter, shared by live deletion and pre-opening restore replay.
-- At most 100 owned ratings per transaction. Use the same advisory namespace as Ratings.
BEGIN;
CREATE TEMP TABLE erasure_games ON COMMIT DROP AS
    SELECT game_id FROM ratings.rating WHERE user_id = :'user_id'
    ORDER BY game_id LIMIT 100;
DO $$
DECLARE affected record;
BEGIN
    FOR affected IN SELECT game_id FROM erasure_games ORDER BY game_id LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended(affected.game_id::text, 285));
    END LOOP;
END $$;
CREATE TEMP TABLE erasure_result ON COMMIT DROP AS
    WITH deleted AS (
        DELETE FROM ratings.rating WHERE user_id = :'user_id'
            AND game_id IN (SELECT game_id FROM erasure_games) RETURNING 1
    ) SELECT count(*) AS deleted FROM deleted;
DO $$
BEGIN
    -- Historical backups can predate either projection or contribution/exact_mean columns.
    IF to_regclass('catalogue.game_community_score') IS NOT NULL THEN
        DELETE FROM catalogue.game_community_score WHERE game_id IN (SELECT game_id FROM erasure_games);
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'catalogue'
            AND table_name = 'game_community_score' AND column_name = 'exact_mean') THEN
            INSERT INTO catalogue.game_community_score(game_id, mean, exact_mean, rating_count)
                SELECT r.game_id, round(round(avg(r.contribution), 18), 1),
                    round(avg(r.contribution), 18), count(*)
                FROM ratings.rating r JOIN erasure_games e USING (game_id)
                JOIN catalogue.game g USING (game_id) GROUP BY r.game_id;
        ELSIF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'ratings'
            AND table_name = 'rating' AND column_name = 'contribution') THEN
            INSERT INTO catalogue.game_community_score(game_id, mean, rating_count)
                SELECT r.game_id, round(round(avg(r.contribution), 18), 1), count(*)
                FROM ratings.rating r JOIN erasure_games e USING (game_id)
                JOIN catalogue.game g USING (game_id) GROUP BY r.game_id;
        ELSE
            INSERT INTO catalogue.game_community_score(game_id, mean, rating_count)
                SELECT r.game_id, round(avg(r.value), 1), count(*)
                FROM ratings.rating r JOIN erasure_games e USING (game_id)
                JOIN catalogue.game g USING (game_id) GROUP BY r.game_id;
        END IF;
    END IF;
END $$;
SELECT deleted FROM erasure_result;
COMMIT;
