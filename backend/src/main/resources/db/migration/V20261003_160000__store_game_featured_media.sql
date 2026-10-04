-- Issue #151: the landscape image and title logo that featured releases present. Expand-only: a
-- new table that older application versions never read.
--
-- At most one image and one logo per Game, each the selection of the featured-media policy over
-- the provider's artworks, screenshots and logos. Only metadata is stored: the provider image
-- identity, its pixel dimensions and transparency, the attribution page, the source and the last
-- time synchronization observed it. Image binaries are never copied, proxied or stored (ADR-0001):
-- the browser loads the approved CDN rendition directly. Synchronization writes a selection in the
-- Game's own transaction and replaces it only with a newer valid one, so missing, invalid or
-- unavailable provider media keep the last valid selection.
CREATE TABLE catalogue.game_featured_media (
    game_id uuid NOT NULL,
    media_role varchar(16) NOT NULL,
    media_kind varchar(16) NOT NULL,
    image_reference varchar(200) NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    transparent boolean NOT NULL,
    source_name varchar(100) NOT NULL,
    source_url varchar(500) NOT NULL,
    observed_at timestamptz NOT NULL,
    CONSTRAINT pk_game_featured_media PRIMARY KEY (game_id, media_role),
    CONSTRAINT fk_game_featured_media_game
        FOREIGN KEY (game_id)
        REFERENCES catalogue.game (game_id),
    CONSTRAINT ck_game_featured_media_role_kind
        CHECK (
            (media_role = 'image' AND media_kind IN ('artwork', 'screenshot'))
            OR (media_role = 'logo' AND media_kind = 'logo')
        ),
    CONSTRAINT ck_game_featured_media_dimensions
        CHECK (width > 0 AND height > 0),
    CONSTRAINT ck_game_featured_media_delivery_reference
        CHECK (
            lower(source_name) = 'igdb'
            AND image_reference ~ '^[A-Za-z0-9_-]+$'
            AND source_url ~ '^https://www\.igdb\.com/games/'
        )
);

COMMENT ON TABLE catalogue.game_featured_media IS
    'Selected featured image and title logo per Game: provider CDN references, never binaries.';
