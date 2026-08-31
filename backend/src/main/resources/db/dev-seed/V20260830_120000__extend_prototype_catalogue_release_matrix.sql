-- Extends the deterministic prototype publication with the release matrix needed to
-- exercise real pagination, platform/region filtering, every date precision, and the
-- product-owned cover fallback. It is disposable development data, not provider truth
-- and not a change to the approved MVP catalogue boundary: the accepted eight-game
-- clickable prototype stays recorded in the product records.
--
-- Dates are absolute so `recent` and `upcoming` never depend on the real date. With
-- the browser gate clock pinned to 2026-08-13 each view holds eight releases, which
-- is two pages plus an incomplete last page at the default page size.

INSERT INTO catalogue.region (region_id, code, display_name) VALUES
    ('20000000-0000-4000-8000-000000000004', 'north-america', 'North America'),
    ('20000000-0000-4000-8000-000000000005', 'japan', 'Japan');

INSERT INTO catalogue.game (game_id, created_at) VALUES
    ('30000000-0000-4000-8000-000000000009', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-00000000000a', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-00000000000b', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-00000000000c', '2026-08-09 10:00:00+00');

-- Crimson Desert keeps an approved IGDB image reference without an attribution URL,
-- which ADR-0001 resolves to the product-owned fallback instead of an unattributed
-- cover. Every other prototype game is product-owned.
INSERT INTO catalogue.game_snapshot (
    publication_id,
    game_id,
    canonical_title,
    slug,
    cover_reference,
    cover_source,
    cover_usage_mode,
    cover_alternative_text,
    cover_usage_status,
    cover_source_url
) VALUES
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000009', 'Metroid Prime 4: Beyond', 'metroid-prime-4-beyond', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Metroid Prime 4: Beyond', 'approved', NULL),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-00000000000a', 'Crimson Desert', 'crimson-desert', 'co7fbz', 'IGDB', 'provider_cdn_reference', 'Carátula de Crimson Desert', 'approved', NULL),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-00000000000b', 'Subnautica 2', 'subnautica-2', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Subnautica 2', 'approved', NULL),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-00000000000c', 'Marvel''s Wolverine', 'marvels-wolverine', '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Portada no disponible de Marvel''s Wolverine', 'approved', NULL);

INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES
    ('40000000-0000-4000-8000-000000000009', '30000000-0000-4000-8000-000000000005', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-00000000000a', '30000000-0000-4000-8000-000000000006', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-00000000000b', '30000000-0000-4000-8000-000000000009', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-00000000000c', '30000000-0000-4000-8000-000000000009', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-00000000000d', '30000000-0000-4000-8000-00000000000a', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-00000000000e', '30000000-0000-4000-8000-00000000000b', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-00000000000f', '30000000-0000-4000-8000-00000000000c', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000010', '30000000-0000-4000-8000-00000000000a', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000011', '30000000-0000-4000-8000-00000000000a', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000012', '30000000-0000-4000-8000-00000000000b', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000013', '30000000-0000-4000-8000-000000000008', '2026-08-09 10:00:00+00'),
    ('40000000-0000-4000-8000-000000000014', '30000000-0000-4000-8000-00000000000b', '2026-08-09 10:00:00+00');

-- Recent window releases (2026-02-13 .. 2026-08-13 for the pinned browser clock).
-- Pragmata and Metroid Prime 4 deliberately repeat an identical effective period so
-- the unique releaseId tie-breaker is exercised by real data.
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
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000009', '30000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'day', '2026-03-06', NULL, NULL, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-00000000000a', '30000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002', 'quarter', NULL, 2026, NULL, 2, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-00000000000b', '30000000-0000-4000-8000-000000000009', '10000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002', 'day', '2026-04-16', NULL, NULL, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-00000000000c', '30000000-0000-4000-8000-000000000009', '10000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000005', 'day', '2026-04-16', NULL, NULL, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-00000000000d', '30000000-0000-4000-8000-00000000000a', '10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'month', NULL, 2026, 5, NULL, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-00000000000e', '30000000-0000-4000-8000-00000000000b', '10000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000004', 'quarter', NULL, 2026, NULL, 1, 'released', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-07-20 10:00:00+00', NULL, 'provider_only', 'not_required');

-- Upcoming window releases (2026-08-13 .. 2027-02-13 for the pinned browser clock),
-- covering scheduled, announced and delayed states next to the retained TBA row.
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
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-00000000000f', '30000000-0000-4000-8000-00000000000c', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'day', '2026-09-25', NULL, NULL, NULL, 'scheduled', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', '2026-08-09 10:00:00+00', 'verified', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000010', '30000000-0000-4000-8000-00000000000a', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002', 'month', NULL, 2026, 10, NULL, 'scheduled', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000011', '30000000-0000-4000-8000-00000000000a', '10000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000002', 'month', NULL, 2026, 10, NULL, 'scheduled', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000012', '30000000-0000-4000-8000-00000000000b', '10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'quarter', NULL, 2026, NULL, 4, 'scheduled', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000013', '30000000-0000-4000-8000-000000000008', '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002', 'year', NULL, 2027, NULL, NULL, 'announced', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'required'),
    ('00000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000014', '30000000-0000-4000-8000-00000000000b', '10000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001', 'quarter', NULL, 2027, NULL, 1, 'delayed', 'product_curated', 'VideoGame Platform clickable prototype', 'prototype_release', NULL, '2026-08-09 10:00:00+00', NULL, 'provider_only', 'not_required');
