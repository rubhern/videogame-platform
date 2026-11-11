-- The latest execution only. Translation/source evidence remains in the existing tables.
CREATE TABLE catalogue.translation_execution (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    execution_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('running','stopping','stopped','completed','attention')),
    after_kind text NOT NULL DEFAULT '' CHECK (after_kind IN ('','GAME_MODE','GENRE','SUMMARY')),
    after_id uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    estimated_total bigint CHECK (estimated_total >= 0),
    processed bigint NOT NULL DEFAULT 0 CHECK (processed >= 0),
    succeeded bigint NOT NULL DEFAULT 0 CHECK (succeeded >= 0),
    skipped bigint NOT NULL DEFAULT 0 CHECK (skipped >= 0),
    failed bigint NOT NULL DEFAULT 0 CHECK (failed >= 0),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    activity_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    reason text CHECK (reason IN ('batch_failed','remaining_failures','worker_failed')),
    CHECK (processed = succeeded + skipped + failed),
    CHECK ((state IN ('running','stopping')) = (lease_until IS NOT NULL)),
    CHECK ((state = 'attention') = (reason IS NOT NULL))
);

-- The publication links represent last-valid results. Join the current attempt by
-- fingerprint using the same normalization as the durable constraint.
CREATE OR REPLACE VIEW catalogue.observability_localization_coverage AS
WITH summary_sources AS MATERIALIZED (
    -- Narrow rows before joins that may spill as translations accumulate. The
    -- regex preserves single spaces and otherwise produces the same bytes as
    -- spanish_source_fingerprint, with no replacement for each word separator.
    SELECT game_id, eligible, CASE WHEN eligible THEN
        encode(sha256(convert_to('en:es:' || btrim(regexp_replace(summary_text COLLATE "C",
            E'[ \t\r\n]{2,}|[\t\r\n]', ' ', 'g')), 'UTF8')), 'hex')
    END AS fingerprint
    FROM (
        SELECT game_id, summary_text,
               coalesce(summary_kind = 'sourced' AND summary_language = 'en'
                   AND summary_source_kind = 'external_provider' AND summary_source_name = 'IGDB', false) AS eligible
        FROM catalogue.game_snapshot
    ) g
), targets AS (
    SELECT 'summary' AS kind, s.eligible, s.fingerprint AS source_fingerprint,
           false AS curated, l.fingerprint AS linked_fingerprint
    FROM summary_sources s
    LEFT JOIN catalogue.game_summary_translation l ON l.game_id = s.game_id
    UNION ALL
    SELECT 'genre', t.source_label IS NOT NULL, catalogue.spanish_source_fingerprint(t.source_label),
           t.label_origin = 'curated',
           CASE WHEN t.label_origin = 'machine' THEN t.translation_fingerprint END
    FROM catalogue.genre t
    UNION ALL
    SELECT 'game_mode', t.source_label IS NOT NULL, catalogue.spanish_source_fingerprint(t.source_label),
           t.label_origin = 'curated',
           CASE WHEN t.label_origin = 'machine' THEN t.translation_fingerprint END
    FROM catalogue.game_mode t
), classified AS (
    SELECT t.kind, t.eligible, t.curated, t.linked_fingerprint IS NOT NULL AS linked_valid,
           attempt.fingerprint, attempt.translated_at IS NOT NULL AS translated, attempt.claimed_until,
           t.curated OR coalesce(attempt.translated_at IS NOT NULL
                AND t.linked_fingerprint = attempt.fingerprint, false) AS current
    FROM targets t
    LEFT JOIN catalogue.content_translation attempt
        ON NOT t.curated AND attempt.fingerprint = t.source_fingerprint
)
SELECT kind,
       CASE WHEN NOT eligible THEN 'not_applicable'
            WHEN current THEN 'current'
            WHEN claimed_until > statement_timestamp() THEN 'busy'
            WHEN translated AND (kind = 'summary' OR (
                SELECT length(label.translated_text) <= 200
                FROM catalogue.content_translation label WHERE label.fingerprint = classified.fingerprint
            )) THEN 'reusable'
            WHEN fingerprint IS NOT NULL THEN 'retryable'
            WHEN linked_valid THEN 'stale'
            ELSE 'missing'
       END AS status,
       CASE WHEN eligible AND current THEN CASE WHEN curated THEN 'curated' ELSE 'machine' END END AS origin,
       eligible AND NOT current AND linked_valid AS last_valid_retained,
       count(*) AS items
FROM classified
GROUP BY 1, 2, 3, 4;

GRANT SELECT ON catalogue.observability_localization_coverage TO videogame_app;
