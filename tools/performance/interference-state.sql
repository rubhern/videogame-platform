-- Offline experiment setup only; never a production migration or localization switch.
BEGIN;
CREATE TEMP TABLE interference_parameters ON COMMIT DROP AS
SELECT :'schema_checksum'::text AS checksum, :'scenario'::text AS scenario;
DO $$
BEGIN
    IF current_database() <> 'videogame_performance' THEN
        RAISE EXCEPTION 'Disposable performance database required';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM performance_data.boundary b, interference_parameters p
        WHERE b.purpose='disposable-performance-only' AND b.profile='growth'
          AND b.schema_checksum=p.checksum) THEN
        RAISE EXCEPTION 'Disposable schema boundary mismatch';
    END IF;
    IF (SELECT scenario FROM interference_parameters) NOT IN
        ('serving-only','serving-sync-reuse','serving-sync-inference') THEN
        RAISE EXCEPTION 'Unknown scenario';
    END IF;
    IF (SELECT count(*) FROM performance_data.interference_fingerprint) NOT BETWEEN 4 AND 10
        OR EXISTS (SELECT 1 FROM performance_data.interference_fingerprint f
            LEFT JOIN catalogue.content_translation c USING(fingerprint)
            WHERE c.translated_text IS NULL) THEN
        RAISE EXCEPTION 'Resolved bounded checkpoint required';
    END IF;
END $$;
-- Force the real acquisition/localization flow to publish from cache or infer.
-- Source/provider rows stay identical. Known curated taxonomy remains current.
DELETE FROM catalogue.game_summary_translation l USING catalogue.game_external_reference r,
    performance_data.interference_game i
WHERE l.game_id=r.game_id AND r.provider='IGDB' AND r.provider_id=i.provider_id;
UPDATE catalogue.genre t SET display_name=t.source_label, label_origin='source', translation_fingerprint=NULL
FROM catalogue.genre_external_reference r
WHERE t.genre_id=r.genre_id AND r.provider='IGDB' AND r.provider_id='985001';
UPDATE catalogue.game_mode t SET display_name=t.source_label, label_origin='source', translation_fingerprint=NULL
FROM catalogue.game_mode_external_reference r
WHERE t.game_mode_id=r.game_mode_id AND r.provider='IGDB' AND r.provider_id='985002';
UPDATE catalogue.content_translation c
SET translated_text=NULL, runtime_revision=NULL, translated_at=NULL, claim_id=NULL, claimed_until=NULL
FROM performance_data.interference_fingerprint f, interference_parameters p
WHERE c.fingerprint=f.fingerprint AND p.scenario='serving-sync-inference';
-- Each scenario starts without an operational run history, on the same checkpoint.
DELETE FROM catalogue.synchronization_run;
COMMIT;
