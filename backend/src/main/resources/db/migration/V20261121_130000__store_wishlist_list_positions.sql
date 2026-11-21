ALTER TABLE catalogue.game_library_entry ADD COLUMN position_key numeric;

WITH ordered AS (
    SELECT w.user_id, w.game_id,
           row_number() OVER (
               PARTITION BY w.user_id
               ORDER BY w.priority DESC NULLS LAST, chosen.timing NULLS LAST,
                        CASE WHEN chosen.timing = 0 THEN chosen.period_start END,
                        CASE WHEN chosen.timing = 1 THEN chosen.period_end END DESC,
                        w.game_id
           ) AS position
    FROM catalogue.game_library_entry w
    LEFT JOIN LATERAL (
        SELECT r.period_start, r.period_end,
               CASE WHEN r.period_end >= (now() AT TIME ZONE 'Europe/Madrid')::date
                    THEN 0 ELSE 1 END AS timing
        FROM catalogue.release_snapshot r
        WHERE r.publication_id = (SELECT publication_id FROM catalogue.catalogue_publication WHERE is_current)
          AND r.game_id = w.game_id
          AND r.period_start IS NOT NULL AND r.release_status NOT IN ('cancelled', 'delayed')
        ORDER BY timing,
                 CASE WHEN r.period_end >= (now() AT TIME ZONE 'Europe/Madrid')::date THEN r.period_start END,
                 CASE WHEN r.period_end < (now() AT TIME ZONE 'Europe/Madrid')::date THEN r.period_end END DESC,
                 r.release_id
        LIMIT 1
    ) chosen ON true
)
UPDATE catalogue.game_library_entry w SET position_key = ordered.position
FROM ordered WHERE w.user_id = ordered.user_id AND w.game_id = ordered.game_id;

ALTER TABLE catalogue.game_library_entry
    ALTER COLUMN position_key SET NOT NULL,
    ADD CONSTRAINT uq_game_library_position UNIQUE (user_id, position_key);

COMMENT ON COLUMN catalogue.game_library_entry.position_key IS
    'Private sort key. A move allocates a key between existing neighbors without rewriting their positions.';
