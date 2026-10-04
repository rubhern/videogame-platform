-- Synthetic fixtures, not real provider Hypes. Visits fixtures were removed by the forward migration.
INSERT INTO catalogue.game_featured_evidence
    (game_id, hypes, first_release_date, eligible_product, source_name, observed_at)
VALUES
    ('30000000-0000-4000-8000-00000000000c', 61, '2026-09-15', true, 'IGDB', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-00000000000a', 42, '2026-10-01', true, 'IGDB', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000008', 93, NULL, true, 'IGDB', '2026-08-09 10:00:00+00');

-- Synthetic evidence supplied by this fixture, never a provider repair or review-status bypass.
UPDATE catalogue.release_snapshot SET release_stage = 'full_release'
WHERE source_kind = 'product_curated' AND game_id IN (
    '30000000-0000-4000-8000-00000000000c',
    '30000000-0000-4000-8000-00000000000a'
);
