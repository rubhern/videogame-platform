-- Deterministic, non-production demonstration data derived from the accepted
-- eight-game clickable prototype. Dates are stable test fixtures, not current
-- provider claims. This location is opt-in and is not part of production migrations.

INSERT INTO catalogue.catalogue_publication (
    publication_id,
    catalogue_version,
    published_at,
    last_synchronized_at,
    source_kind,
    source_name,
    is_current
) VALUES (
    '00000000-0000-4000-8000-000000000001',
    'prototype-catalogue-v1',
    '2026-08-09 10:00:00+00',
    '2026-08-09 10:00:00+00',
    'product_curated',
    'VideoGame Platform clickable prototype',
    true
);

INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES
    ('10000000-0000-4000-8000-000000000001', 'playstation-5', 'PlayStation 5'),
    ('10000000-0000-4000-8000-000000000002', 'nintendo-switch-2', 'Nintendo Switch 2'),
    ('10000000-0000-4000-8000-000000000003', 'windows-pc', 'Windows PC'),
    ('10000000-0000-4000-8000-000000000004', 'xbox-series', 'Xbox Series X|S');

INSERT INTO catalogue.region (region_id, code, display_name) VALUES
    ('20000000-0000-4000-8000-000000000001', 'worldwide', 'Worldwide'),
    ('20000000-0000-4000-8000-000000000002', 'europe', 'Europe'),
    ('20000000-0000-4000-8000-000000000003', 'unknown', 'Unknown');

INSERT INTO catalogue.game (game_id, created_at) VALUES
    ('30000000-0000-4000-8000-000000000001', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000002', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000003', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000004', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000005', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000006', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000007', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000008', '2026-08-09 10:00:00+00');

INSERT INTO catalogue.game_snapshot (
    publication_id,
    game_id,
    canonical_title,
    slug,
    cover_reference,
    cover_source,
    cover_usage_mode,
    cover_alternative_text,
    cover_usage_status
) VALUES
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', 'Death Stranding 2: On the Beach', 'death-stranding-2-on-the-beach', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Death Stranding 2: On the Beach', 'approved'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000002', 'Donkey Kong Bananza', 'donkey-kong-bananza', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Donkey Kong Bananza', 'approved'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000003', 'Ghost of Yōtei', 'ghost-of-yotei', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Ghost of Yōtei', 'approved'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000004', 'Hollow Knight: Silksong', 'hollow-knight-silksong', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Hollow Knight: Silksong', 'approved'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000005', 'Resident Evil Requiem', 'resident-evil-requiem', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Resident Evil Requiem', 'approved'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000006', 'Pragmata', 'pragmata', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Pragmata', 'approved'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000007', 'Fable', 'fable', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Fable', 'approved'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000008', 'The Witcher IV', 'the-witcher-iv', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de The Witcher IV', 'approved');

INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES
    ('40000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000002', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000003', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000004', '30000000-0000-4000-8000-000000000004', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000005', '30000000-0000-4000-8000-000000000005', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000006', '30000000-0000-4000-8000-000000000006', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000007', '30000000-0000-4000-8000-000000000007', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000008', '30000000-0000-4000-8000-000000000008', '2026-08-09 10:00:00+00');

INSERT INTO catalogue.release_snapshot (
    publication_id,
    release_id,
    game_id,
    platform_id,
    region_id,
    date_precision,
    exact_date,
    release_year,
    release_month,
    release_quarter,
    release_status,
    source_kind,
    source_name,
    source_entity_type,
    provider_updated_at,
    last_synchronized_at,
    last_verified_at,
    verification_level,
    review_status
) VALUES
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002', 'day', '2025-06-26', NULL, NULL, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002', 'day', '2025-07-17', NULL, NULL, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002', 'day', '2025-10-02', NULL, NULL, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000004', '30000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'month', NULL, 2025, 9, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000005', '30000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002', 'day', '2026-02-27', NULL, NULL, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000006', '30000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'quarter', NULL, 2026, NULL, 2, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000007', '30000000-0000-4000-8000-000000000007', '10000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000001', 'year', NULL, 2027, NULL, NULL, 'scheduled', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000008', '30000000-0000-4000-8000-000000000008', '10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000003', 'unknown', NULL, NULL, NULL, NULL, 'announced', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'required');
