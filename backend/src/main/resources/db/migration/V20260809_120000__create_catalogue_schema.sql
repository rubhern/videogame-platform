CREATE SCHEMA catalogue;

REVOKE ALL ON SCHEMA catalogue FROM PUBLIC;
GRANT USAGE ON SCHEMA catalogue TO videogame_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA catalogue
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO videogame_app;

CREATE TABLE catalogue.catalogue_publication (
    publication_id uuid PRIMARY KEY,
    catalogue_version varchar(100) NOT NULL,
    published_at timestamptz NOT NULL,
    last_synchronized_at timestamptz NOT NULL,
    source_kind varchar(32) NOT NULL,
    source_name varchar(200) NOT NULL,
    is_current boolean NOT NULL DEFAULT false,
    CONSTRAINT uq_catalogue_publication_version UNIQUE (catalogue_version),
    CONSTRAINT ck_catalogue_publication_version_not_blank
        CHECK (btrim(catalogue_version) <> ''),
    CONSTRAINT ck_catalogue_publication_source_kind
        CHECK (source_kind IN ('external_provider', 'product_curated', 'official_source')),
    CONSTRAINT ck_catalogue_publication_source_name_not_blank
        CHECK (btrim(source_name) <> ''),
    CONSTRAINT ck_catalogue_publication_timestamps
        CHECK (published_at >= last_synchronized_at)
);

CREATE UNIQUE INDEX uq_catalogue_publication_current
    ON catalogue.catalogue_publication (is_current)
    WHERE is_current;

CREATE TABLE catalogue.game (
    game_id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL
);

CREATE TABLE catalogue.game_snapshot (
    publication_id uuid NOT NULL,
    game_id uuid NOT NULL,
    canonical_title varchar(300) NOT NULL,
    slug varchar(200) NOT NULL,
    cover_reference varchar(500) NOT NULL,
    cover_source varchar(200) NOT NULL,
    cover_usage_mode varchar(32) NOT NULL,
    cover_alternative_text varchar(500) NOT NULL,
    cover_usage_status varchar(32) NOT NULL,
    PRIMARY KEY (publication_id, game_id),
    CONSTRAINT fk_game_snapshot_publication
        FOREIGN KEY (publication_id)
        REFERENCES catalogue.catalogue_publication (publication_id),
    CONSTRAINT fk_game_snapshot_game
        FOREIGN KEY (game_id)
        REFERENCES catalogue.game (game_id),
    CONSTRAINT uq_game_snapshot_slug UNIQUE (publication_id, slug),
    CONSTRAINT ck_game_snapshot_title_not_blank
        CHECK (btrim(canonical_title) <> ''),
    CONSTRAINT ck_game_snapshot_slug
        CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_game_snapshot_cover_reference_not_blank
        CHECK (btrim(cover_reference) <> ''),
    CONSTRAINT ck_game_snapshot_cover_source_not_blank
        CHECK (btrim(cover_source) <> ''),
    CONSTRAINT ck_game_snapshot_cover_usage_mode
        CHECK (cover_usage_mode IN ('provider_cdn_reference', 'product_owned')),
    CONSTRAINT ck_game_snapshot_cover_alternative_text_not_blank
        CHECK (btrim(cover_alternative_text) <> ''),
    CONSTRAINT ck_game_snapshot_primary_cover_approved
        CHECK (cover_usage_status = 'approved')
);

CREATE TABLE catalogue.platform (
    platform_id uuid PRIMARY KEY,
    code varchar(100) NOT NULL,
    display_name varchar(200) NOT NULL,
    CONSTRAINT uq_platform_code UNIQUE (code),
    CONSTRAINT ck_platform_code CHECK (code ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_platform_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE catalogue.region (
    region_id uuid PRIMARY KEY,
    code varchar(100) NOT NULL,
    display_name varchar(200) NOT NULL,
    CONSTRAINT uq_region_code UNIQUE (code),
    CONSTRAINT ck_region_code CHECK (code ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_region_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE catalogue.game_release (
    release_id uuid NOT NULL,
    game_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (release_id, game_id),
    CONSTRAINT uq_game_release_id UNIQUE (release_id),
    CONSTRAINT fk_game_release_game
        FOREIGN KEY (game_id)
        REFERENCES catalogue.game (game_id)
);

CREATE TABLE catalogue.release_snapshot (
    publication_id uuid NOT NULL,
    release_id uuid NOT NULL,
    game_id uuid NOT NULL,
    platform_id uuid NOT NULL,
    region_id uuid NOT NULL,
    date_precision varchar(16) NOT NULL,
    exact_date date,
    release_year smallint,
    release_month smallint,
    release_quarter smallint,
    release_status varchar(16) NOT NULL,
    source_kind varchar(32) NOT NULL,
    source_name varchar(200) NOT NULL,
    source_entity_type varchar(100) NOT NULL,
    provider_updated_at timestamptz,
    last_synchronized_at timestamptz NOT NULL,
    last_verified_at timestamptz,
    verification_level varchar(16) NOT NULL,
    review_status varchar(16) NOT NULL,
    PRIMARY KEY (publication_id, release_id),
    CONSTRAINT fk_release_snapshot_game_snapshot
        FOREIGN KEY (publication_id, game_id)
        REFERENCES catalogue.game_snapshot (publication_id, game_id),
    CONSTRAINT fk_release_snapshot_release
        FOREIGN KEY (release_id, game_id)
        REFERENCES catalogue.game_release (release_id, game_id),
    CONSTRAINT fk_release_snapshot_platform
        FOREIGN KEY (platform_id)
        REFERENCES catalogue.platform (platform_id),
    CONSTRAINT fk_release_snapshot_region
        FOREIGN KEY (region_id)
        REFERENCES catalogue.region (region_id),
    CONSTRAINT ck_release_snapshot_date_precision
        CHECK (date_precision IN ('day', 'month', 'quarter', 'year', 'unknown')),
    CONSTRAINT ck_release_snapshot_date_value
        CHECK (
            (date_precision = 'day'
                AND exact_date IS NOT NULL
                AND release_year IS NULL
                AND release_month IS NULL
                AND release_quarter IS NULL)
            OR (date_precision = 'month'
                AND exact_date IS NULL
                AND release_year BETWEEN 1 AND 9999
                AND release_month BETWEEN 1 AND 12
                AND release_quarter IS NULL)
            OR (date_precision = 'quarter'
                AND exact_date IS NULL
                AND release_year BETWEEN 1 AND 9999
                AND release_month IS NULL
                AND release_quarter BETWEEN 1 AND 4)
            OR (date_precision = 'year'
                AND exact_date IS NULL
                AND release_year BETWEEN 1 AND 9999
                AND release_month IS NULL
                AND release_quarter IS NULL)
            OR (date_precision = 'unknown'
                AND exact_date IS NULL
                AND release_year IS NULL
                AND release_month IS NULL
                AND release_quarter IS NULL)
        ),
    CONSTRAINT ck_release_snapshot_status
        CHECK (release_status IN ('announced', 'scheduled', 'released', 'delayed', 'cancelled', 'unknown')),
    CONSTRAINT ck_release_snapshot_source_kind
        CHECK (source_kind IN ('external_provider', 'product_curated', 'official_source')),
    CONSTRAINT ck_release_snapshot_source_name_not_blank
        CHECK (btrim(source_name) <> ''),
    CONSTRAINT ck_release_snapshot_source_entity_type_not_blank
        CHECK (btrim(source_entity_type) <> ''),
    CONSTRAINT ck_release_snapshot_verification_level
        CHECK (verification_level IN ('provider_only', 'verified')),
    CONSTRAINT ck_release_snapshot_review_status
        CHECK (review_status IN ('not_required', 'required')),
    CONSTRAINT uq_release_snapshot_tuple
        UNIQUE NULLS NOT DISTINCT (
            publication_id,
            game_id,
            platform_id,
            region_id,
            date_precision,
            exact_date,
            release_year,
            release_month,
            release_quarter,
            release_status
        )
);

CREATE INDEX ix_release_snapshot_publication_platform_region
    ON catalogue.release_snapshot (publication_id, platform_id, region_id);

CREATE INDEX ix_release_snapshot_publication_date
    ON catalogue.release_snapshot (
        publication_id,
        date_precision,
        exact_date,
        release_year,
        release_month,
        release_quarter,
        game_id
    );
