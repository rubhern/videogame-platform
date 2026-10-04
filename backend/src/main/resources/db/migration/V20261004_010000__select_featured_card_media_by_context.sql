-- Issue #151: independently selected card media, alongside the existing hero image.
-- Preserve previously applied migrations and valid hero/logo evidence. A card slot is filled by
-- normal synchronization. Retain suitable existing image evidence as a transitional card
-- selection; unsuitable legacy media use the designed cover/product fallback.
ALTER TABLE catalogue.game_featured_media
    DROP CONSTRAINT ck_game_featured_media_role_kind;
ALTER TABLE catalogue.game_featured_media
    ADD CONSTRAINT ck_game_featured_media_role_kind CHECK (
        (media_role IN ('image', 'card_image') AND media_kind IN ('artwork', 'screenshot'))
        OR (media_role = 'logo' AND media_kind = 'logo')
    );
COMMENT ON TABLE catalogue.game_featured_media IS
    'Selected hero and card images and optional secondary-card logo: CDN references, never binaries.';

-- Avoid degrading valid existing cards during the first refresh. This retains evidence, it does
-- not compare unseen provider candidates; subsequent synchronization applies the full policy.
INSERT INTO catalogue.game_featured_media (
    game_id, media_role, media_kind, image_reference, width, height, transparent,
    source_name, source_url, observed_at
)
SELECT game_id, 'card_image', media_kind, image_reference, width, height, transparent,
       source_name, source_url, observed_at
FROM catalogue.game_featured_media
WHERE media_role = 'image' AND NOT transparent AND width >= 640 AND height >= 360
  AND 2::bigint * width >= 3::bigint * height
  AND width::numeric / height BETWEEN (16.0 / 9.4) * 0.8 AND (16.0 / 9.4) / 0.8;
