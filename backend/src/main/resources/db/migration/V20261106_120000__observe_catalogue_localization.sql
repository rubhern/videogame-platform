-- #452: durable localization coverage for the private Grafana reader. No new stored state.
--
-- Idempotent on purpose: an installed runtime that cannot take a newer application yet may apply
-- this exact file ahead of its Flyway history (scripts/private_production_acceptance.py); the
-- later Flyway run then replaces the view with itself.
--
-- Aggregates only, without Game, taxonomy, fingerprint or text identities. Each target has exactly
-- one status; status meanings are owned by docs/development/observability.md.
--
-- Raw source equality replaces hashing on the hot path: an exact match implies the same fingerprint,
-- and at most one content row can match because the fingerprint is the primary key of a function of
-- source_text. Fingerprints are computed only when a linked source no longer matches byte for byte.
-- Hashing every eligible summary takes over 4 s at 157k rows, above the reader's 3 s statement limit.
CREATE OR REPLACE VIEW catalogue.observability_localization_coverage AS
WITH targets AS (
    SELECT 'summary' AS kind,
           g.summary_kind = 'sourced' AND g.summary_language = 'en'
               AND g.summary_source_kind = 'external_provider' AND g.summary_source_name = 'IGDB' AS eligible,
           g.summary_text AS source,
           false AS curated,
           l.fingerprint AS linked_fingerprint
    FROM catalogue.game_snapshot g
    LEFT JOIN catalogue.game_summary_translation l ON l.game_id = g.game_id
    UNION ALL
    SELECT 'genre', t.source_label IS NOT NULL, t.source_label, t.label_origin = 'curated', t.translation_fingerprint
    FROM catalogue.genre t
    UNION ALL
    SELECT 'game_mode', t.source_label IS NOT NULL, t.source_label, t.label_origin = 'curated', t.translation_fingerprint
    FROM catalogue.game_mode t
), classified AS (
    SELECT t.kind, t.eligible, t.curated, t.linked_fingerprint, attempt.translated_text, attempt.claimed_until,
           attempt.fingerprint AS attempt_fingerprint,
           CASE WHEN NOT t.eligible THEN false
                WHEN t.curated THEN true
                WHEN t.linked_fingerprint IS NULL THEN false
                WHEN linked.source_text = t.source THEN true
                ELSE t.linked_fingerprint = catalogue.spanish_source_fingerprint(t.source)
           END AS current
    FROM targets t
    LEFT JOIN catalogue.content_translation linked ON linked.fingerprint = t.linked_fingerprint
    LEFT JOIN catalogue.content_translation attempt ON t.eligible AND attempt.source_text = t.source
)
SELECT kind,
       CASE WHEN NOT eligible THEN 'not_applicable'
            WHEN current THEN 'current'
            WHEN claimed_until > clock_timestamp() THEN 'busy'
            WHEN translated_text IS NOT NULL THEN 'reusable'
            WHEN attempt_fingerprint IS NOT NULL THEN 'retryable'
            WHEN linked_fingerprint IS NOT NULL THEN 'stale'
            ELSE 'missing'
       END AS status,
       CASE WHEN current THEN CASE WHEN curated THEN 'curated' ELSE 'machine' END END AS origin,
       eligible AND NOT current AND linked_fingerprint IS NOT NULL AS last_valid_retained,
       count(*) AS items
FROM classified
GROUP BY 1, 2, 3, 4;
COMMENT ON VIEW catalogue.observability_localization_coverage IS
    'Current localization coverage derived from stored source and translation evidence, without identities or a second ledger.';

-- Override application default table DML grants: this is a read contract.
REVOKE ALL ON catalogue.observability_localization_coverage FROM PUBLIC, videogame_app;
