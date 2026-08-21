ALTER TABLE catalogue.game_snapshot
    ADD COLUMN cover_source_url varchar(500);

UPDATE catalogue.game_snapshot gs
SET cover_source_url = ger.provider_url
FROM catalogue.game_external_reference ger
WHERE ger.game_id = gs.game_id
  AND lower(ger.provider) = lower(gs.cover_source)
  AND ger.provider_entity_type = 'game'
  AND gs.cover_usage_mode = 'provider_cdn_reference';

ALTER TABLE catalogue.game_snapshot
    ADD CONSTRAINT ck_game_snapshot_cover_delivery_reference
    CHECK (
        (
            cover_usage_mode = 'product_owned'
            AND cover_source_url IS NULL
            AND cover_reference ~ '^/assets/covers/[A-Za-z0-9._/-]+$'
        )
        OR (
            cover_usage_mode = 'provider_cdn_reference'
            AND lower(cover_source) = 'igdb'
            AND cover_reference ~ '^[A-Za-z0-9_-]+$'
            AND (
                cover_source_url IS NULL
                OR cover_source_url ~ '^https://www\.igdb\.com/games/'
            )
        )
    );

ALTER TABLE catalogue.release_snapshot
    ADD COLUMN period_start date GENERATED ALWAYS AS (
        CASE date_precision
            WHEN 'day' THEN exact_date
            WHEN 'month' THEN make_date(release_year::integer, release_month::integer, 1)
            WHEN 'quarter' THEN make_date(
                release_year::integer,
                ((release_quarter::integer - 1) * 3) + 1,
                1
            )
            WHEN 'year' THEN make_date(release_year::integer, 1, 1)
            ELSE NULL
        END
    ) STORED,
    ADD COLUMN period_end date GENERATED ALWAYS AS (
        CASE date_precision
            WHEN 'day' THEN exact_date
            WHEN 'month' THEN (
                make_date(release_year::integer, release_month::integer, 1)
                + interval '1 month - 1 day'
            )::date
            WHEN 'quarter' THEN (
                make_date(
                    release_year::integer,
                    ((release_quarter::integer - 1) * 3) + 1,
                    1
                ) + interval '3 months - 1 day'
            )::date
            WHEN 'year' THEN make_date(release_year::integer, 12, 31)
            ELSE NULL
        END
    ) STORED;

ALTER TABLE catalogue.release_snapshot
    ADD CONSTRAINT ck_release_snapshot_period_bounds
    CHECK (
        (date_precision = 'unknown' AND period_start IS NULL AND period_end IS NULL)
        OR (
            date_precision <> 'unknown'
            AND period_start IS NOT NULL
            AND period_end IS NOT NULL
            AND period_start <= period_end
        )
    );
