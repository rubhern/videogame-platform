-- #178: Platform and Region taxonomy is acquired from accepted releases through typed provider
-- external references, superseding the ADR-0017 clause that the normal migration installs the
-- approved taxonomy. Product taxonomy keeps its internal UUID identity; provider IDs are external
-- references only and never become product identity. Runtime acquisition resolves a provider
-- entity by its ID, reuses the known product taxonomy, or creates a new product entity plus its
-- reference as part of the accepted state. It never merges provider entities by title/name/slug.

CREATE TABLE catalogue.platform_external_reference (
    provider varchar(100) NOT NULL,
    provider_id varchar(200) NOT NULL,
    platform_id uuid NOT NULL,
    PRIMARY KEY (provider, provider_id),
    CONSTRAINT uq_platform_external_reference_platform UNIQUE (provider, platform_id),
    CONSTRAINT fk_platform_external_reference_platform
        FOREIGN KEY (platform_id) REFERENCES catalogue.platform (platform_id),
    CONSTRAINT ck_platform_external_reference_provider_not_blank CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_platform_external_reference_provider_id_not_blank CHECK (btrim(provider_id) <> '')
);

CREATE TABLE catalogue.region_external_reference (
    provider varchar(100) NOT NULL,
    provider_id varchar(200) NOT NULL,
    region_id uuid NOT NULL,
    PRIMARY KEY (provider, provider_id),
    CONSTRAINT uq_region_external_reference_region UNIQUE (provider, region_id),
    CONSTRAINT fk_region_external_reference_region
        FOREIGN KEY (region_id) REFERENCES catalogue.region (region_id),
    CONSTRAINT ck_region_external_reference_provider_not_blank CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_region_external_reference_provider_id_not_blank CHECK (btrim(provider_id) <> '')
);

-- Backfill IGDB provider references for the platforms/regions that predate release-driven
-- acquisition, so a real synchronization reuses these product identities instead of creating
-- duplicates. These are deliberate one-time curated mappings by provider entity ID; they preserve
-- existing product identity and are not the runtime name/slug matching the lifecycle forbids.
-- Rows are matched by product code only to locate the pre-seeded identities; the 'unknown' region
-- stays a product-only sentinel without a provider reference.
INSERT INTO catalogue.platform_external_reference (provider, provider_id, platform_id)
SELECT 'IGDB', mapping.provider_id, platform.platform_id
FROM catalogue.platform platform
JOIN (VALUES
    ('playstation-5', '167'),
    ('nintendo-switch-2', '508'),
    ('windows-pc', '6'),
    ('xbox-series', '169')
) AS mapping(code, provider_id) ON mapping.code = platform.code
ON CONFLICT DO NOTHING;

INSERT INTO catalogue.region_external_reference (provider, provider_id, region_id)
SELECT 'IGDB', mapping.provider_id, region.region_id
FROM catalogue.region region
JOIN (VALUES
    ('worldwide', '8'),
    ('europe', '1'),
    ('north-america', '2'),
    ('japan', '5')
) AS mapping(code, provider_id) ON mapping.code = region.code
ON CONFLICT DO NOTHING;
