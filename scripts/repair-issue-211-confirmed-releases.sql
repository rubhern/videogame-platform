-- One-off, owner-authorized #211 repair for the inspected private-dev examples:
-- Resident Evil Requiem and Backrooms: Code Yellow. Run with ON_ERROR_STOP=1.
--
-- There is no pre-#177 catalogue history. These explicitly accepted current tuples
-- are an allowlist, not a heuristic claiming every review-required row is false.
-- Review remains a legitimate blocker. No other release is changed, and a changed
-- identity, date, negative signal or verification aborts the entire repair.
-- Provider evidence, verification and freshness timestamps are preserved.

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
DECLARE
    publication uuid;
    expected record;
    actual record;
    repaired integer := 0;
BEGIN
    -- Serialize with normal synchronization's catalogue revision lock.
    SELECT publication_id INTO STRICT publication
    FROM catalogue.catalogue_publication WHERE is_current FOR UPDATE;

    FOR expected IN
        SELECT * FROM (VALUES
            ('347668', '752219', '6',   '8', DATE '2026-02-27'),
            ('347668', '752220', '167', '8', DATE '2026-02-27'),
            ('347668', '752221', '169', '8', DATE '2026-02-27'),
            ('347668', '800758', '508', '8', DATE '2026-02-27'),
            ('416594', '961960', '6',   '8', DATE '2026-09-21')
        ) AS evidence(game_ref, release_ref, platform_ref, region_ref, release_date)
        ORDER BY game_ref, release_ref
    LOOP
        SELECT rs.* INTO actual
        FROM catalogue.release_external_reference rr
        JOIN catalogue.release_snapshot rs USING (release_id, game_id)
        JOIN catalogue.game_external_reference gr USING (game_id)
        JOIN catalogue.platform_external_reference px USING (platform_id)
        JOIN catalogue.region_external_reference rx USING (region_id)
        WHERE rr.provider = 'IGDB' AND rr.provider_id = expected.release_ref
          AND gr.provider = 'IGDB' AND gr.provider_entity_type = 'game'
          AND gr.provider_id = expected.game_ref
          AND px.provider = 'IGDB' AND px.provider_id = expected.platform_ref
          AND rx.provider = 'IGDB' AND rx.provider_id = expected.region_ref
          AND rs.publication_id = publication
        FOR UPDATE OF rs;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Issue 211: expected release identity missing: %', expected.release_ref;
        END IF;
        IF actual.date_precision <> 'day'
            OR actual.exact_date IS DISTINCT FROM expected.release_date
            OR actual.exact_date > (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Madrid')::date
            OR actual.release_status <> 'announced'
            OR actual.source_kind <> 'external_provider'
            OR actual.source_name <> 'IGDB'
            OR actual.source_entity_type <> 'release_date'
            OR actual.verification_level <> 'provider_only'
            OR actual.last_verified_at IS NOT NULL
            OR actual.review_status NOT IN ('required', 'not_required') THEN
            RAISE EXCEPTION 'Issue 211: release evidence changed; repair aborted: %', expected.release_ref;
        END IF;

        IF actual.review_status = 'required' THEN
            UPDATE catalogue.release_snapshot
            SET review_status = 'not_required'
            WHERE publication_id = publication AND release_id = actual.release_id;
            repaired := repaired + 1;
        END IF;
    END LOOP;

    IF repaired > 0 THEN
        UPDATE catalogue.catalogue_publication
        SET catalogue_version = 'issue-211-review-repair-' || gen_random_uuid()::text,
            published_at = CURRENT_TIMESTAMP
        WHERE publication_id = publication;
    END IF;
    RAISE NOTICE 'Issue 211: repaired % confirmed releases', repaired;
END $$;

COMMIT;
