package com.videogameplatform.catalogue.adapter.persistence.synchronization;

/** Writes affect only the selected Game; there are no catalogue-copy statements. */
final class CatalogueSynchronizationSql {
    private CatalogueSynchronizationSql() {}

    static final String PLATFORM_CODES =
            """
            SELECT code, platform_id::text AS taxonomy_id
            FROM catalogue.platform
            """;

    static final String REGION_CODES =
            """
            SELECT code, region_id::text AS taxonomy_id
            FROM catalogue.region
            """;

    static final String INSERT_GAME =
            """
            INSERT INTO catalogue.game (game_id, created_at)
            VALUES (CAST(:gameId AS uuid), :createdAt)
            """;

    static final String INSERT_EXTERNAL_REFERENCE =
            """
            INSERT INTO catalogue.game_external_reference (
                game_id, provider, provider_entity_type, provider_id, provider_url)
            VALUES (CAST(:gameId AS uuid), :provider, 'game', :providerId, :providerUrl)
            """;

    static final String INSERT_GAME_SNAPSHOT =
            """
            INSERT INTO catalogue.game_snapshot (
                publication_id, game_id, canonical_title, slug, cover_reference, cover_source,
                cover_usage_mode, cover_alternative_text, cover_source_url, cover_usage_status)
            VALUES (
                CAST(:publicationId AS uuid), CAST(:gameId AS uuid), :canonicalTitle, :slug,
                :coverReference, :coverSource, :coverUsageMode, :coverAlternativeText,
                :coverSourceUrl, 'approved')
            """;

    static final String UPDATE_GAME_SNAPSHOT_WITH_COVER =
            """
            UPDATE catalogue.game_snapshot
            SET canonical_title = :canonicalTitle,
                cover_reference = :coverReference,
                cover_source = :coverSource,
                cover_usage_mode = :coverUsageMode,
                cover_alternative_text = :coverAlternativeText,
                cover_source_url = :coverSourceUrl
            WHERE publication_id = CAST(:publicationId AS uuid)
              AND game_id = CAST(:gameId AS uuid)
              AND (canonical_title, cover_reference, cover_source, cover_usage_mode,
                   cover_alternative_text, cover_source_url) IS DISTINCT FROM
                  (:canonicalTitle, :coverReference, :coverSource, :coverUsageMode,
                   :coverAlternativeText, :coverSourceUrl)
            """;

    static final String INSERT_RELEASE_IDENTITY =
            """
            INSERT INTO catalogue.game_release (release_id, game_id, created_at)
            VALUES (CAST(:releaseId AS uuid), CAST(:gameId AS uuid), :createdAt)
            ON CONFLICT DO NOTHING
            """;

    static final String INSERT_RELEASE_SNAPSHOT =
            """
            INSERT INTO catalogue.release_snapshot (
                publication_id, release_id, game_id, platform_id, region_id,
                date_precision, exact_date, release_year, release_month, release_quarter,
                release_status, source_kind, source_name, source_entity_type,
                provider_updated_at, last_synchronized_at, last_verified_at,
                verification_level, review_status)
            VALUES (
                CAST(:publicationId AS uuid), CAST(:releaseId AS uuid), CAST(:gameId AS uuid),
                CAST(:platformId AS uuid), CAST(:regionId AS uuid),
                :datePrecision, :exactDate, :releaseYear, :releaseMonth, :releaseQuarter,
                :releaseStatus, :sourceKind, :sourceName, :sourceEntityType,
                :providerUpdatedAt, :lastSynchronizedAt, :lastVerifiedAt,
                :verificationLevel, :reviewStatus)
            ON CONFLICT (publication_id, release_id) DO UPDATE SET
                platform_id = EXCLUDED.platform_id, region_id = EXCLUDED.region_id,
                date_precision = EXCLUDED.date_precision, exact_date = EXCLUDED.exact_date,
                release_year = EXCLUDED.release_year, release_month = EXCLUDED.release_month,
                release_quarter = EXCLUDED.release_quarter, release_status = EXCLUDED.release_status,
                provider_updated_at = EXCLUDED.provider_updated_at,
                last_synchronized_at = EXCLUDED.last_synchronized_at,
                last_verified_at = EXCLUDED.last_verified_at,
                verification_level = EXCLUDED.verification_level, review_status = EXCLUDED.review_status
            WHERE (release_snapshot.platform_id, release_snapshot.region_id,
                release_snapshot.date_precision, release_snapshot.exact_date,
                release_snapshot.release_year, release_snapshot.release_month,
                release_snapshot.release_quarter, release_snapshot.release_status,
                release_snapshot.verification_level, release_snapshot.review_status,
                release_snapshot.last_verified_at)
                IS DISTINCT FROM (EXCLUDED.platform_id, EXCLUDED.region_id,
                EXCLUDED.date_precision, EXCLUDED.exact_date,
                EXCLUDED.release_year, EXCLUDED.release_month,
                EXCLUDED.release_quarter, EXCLUDED.release_status,
                EXCLUDED.verification_level, EXCLUDED.review_status, EXCLUDED.last_verified_at)
            """;
}
