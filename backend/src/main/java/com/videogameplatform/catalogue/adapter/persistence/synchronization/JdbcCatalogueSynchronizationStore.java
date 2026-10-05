package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderCompany;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderSummary;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderTerm;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.DetailsWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.FeaturedEvidenceWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.MediaWrite;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import com.videogameplatform.catalogue.domain.RegionLabel;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/** Fenced operational state and atomic writes of one Game and its externally identified releases. */
public final class JdbcCatalogueSynchronizationStore implements CatalogueSynchronizationStore {
    private static final String PRODUCT_OWNED_COVER = "product_owned";
    private static final String PROVIDER_COVER = "provider_cdn_reference";
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final String provider;
    private final java.util.function.Consumer<String> listingChanged;
    private final java.util.function.Consumer<
                    com.videogameplatform.catalogue.application.details.GameDetailsResult.Term>
            genreLabelChanged;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcCatalogueSynchronizationStore(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations transaction,
            String provider,
            java.util.function.Consumer<String> listingChanged) {
        this(jdbc, transaction, provider, listingChanged, ignored -> {});
    }

    public JdbcCatalogueSynchronizationStore(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations transaction,
            String provider,
            java.util.function.Consumer<String> listingChanged,
            java.util.function.Consumer<
                            com.videogameplatform.catalogue.application.details.GameDetailsResult
                                    .Term>
                    genreLabelChanged) {
        this.genreLabelChanged = genreLabelChanged;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.provider = provider;
        this.listingChanged = listingChanged;
    }

    @Override
    public Optional<UUID> beginRun(
            String source,
            CatalogueSynchronizationRequest request,
            Instant started,
            Duration abandonAfter) {
        return transaction.execute(
                status -> {
                    jdbc.update(
                            """
                    UPDATE catalogue.synchronization_run
                    SET run_status = 'abandoned', completed_at = :now, outcome_code = 'SYNCHRONIZATION_ABANDONED'
                    WHERE provider = :provider AND run_status = 'running' AND heartbeat_at < :before
                    """,
                            new MapSqlParameterSource()
                                    .addValue("provider", source)
                                    .addValue("now", timestamp(started))
                                    .addValue("before", timestamp(started.minus(abandonAfter))));
                    UUID id = UUID.randomUUID();
                    int inserted =
                            jdbc.update(
                                    """
                    INSERT INTO catalogue.synchronization_run(
                        run_id,provider,window_from,window_to,started_at,heartbeat_at,run_status)
                    VALUES (:id,:provider,:from,:to,:started,:started,'running')
                    ON CONFLICT (provider) WHERE run_status = 'running' DO NOTHING
                    """,
                                    new MapSqlParameterSource()
                                            .addValue("id", id)
                                            .addValue("provider", source)
                                            .addValue("from", request.from())
                                            .addValue("to", request.to())
                                            .addValue("started", timestamp(started)));
                    return inserted == 1 ? Optional.of(id) : Optional.empty();
                });
    }

    /** Locks the run token until commit; an abandoned worker cannot publish or update its run. */
    private void fence(UUID runId) {
        List<UUID> rows =
                jdbc.query(
                        """
                SELECT run_id FROM catalogue.synchronization_run
                WHERE run_id = :id AND provider = :provider AND run_status = 'running' FOR UPDATE
                """,
                        Map.of("id", runId, "provider", provider),
                        (rs, row) -> rs.getObject(1, UUID.class));
        if (rows.isEmpty()) {
            throw new SynchronizationWriteException(
                    SynchronizationWriteException.Reason.OWNERSHIP_LOST);
        }
        jdbc.update(
                "UPDATE catalogue.synchronization_run SET heartbeat_at=CURRENT_TIMESTAMP WHERE run_id=:id",
                Map.of("id", runId));
    }

    @Override
    public void heartbeat(UUID runId) {
        transaction.executeWithoutResult(
                status -> {
                    fence(runId);
                });
    }

    @Override
    public Optional<GameState> loadGame(String providerId, int maxReleases) {
        return transaction.execute(
                status -> {
                    List<GameState> games =
                            jdbc.query(
                                    """
                    SELECT s.* FROM catalogue.game_external_reference r
                    JOIN catalogue.game_snapshot s ON s.game_id=r.game_id
                    WHERE r.provider=:provider AND r.provider_entity_type='game' AND r.provider_id=:id
                    """,
                                    Map.of("provider", provider, "id", providerId),
                                    (rs, row) -> {
                                        CoverSelection cover =
                                                PROVIDER_COVER.equals(
                                                                rs.getString("cover_usage_mode"))
                                                        ? new CoverSelection.Provider(
                                                                rs.getString("cover_reference"),
                                                                rs.getString("cover_source_url"),
                                                                rs.getString(
                                                                        "cover_alternative_text"))
                                                        : new CoverSelection.ProductFallback(
                                                                rs.getString("cover_reference"),
                                                                rs.getString("cover_source"),
                                                                rs.getString(
                                                                        "cover_alternative_text"));
                                        return new GameState(
                                                rs.getObject("game_id", UUID.class),
                                                rs.getString("canonical_title"),
                                                rs.getString("slug"),
                                                cover,
                                                Map.of());
                                    });
                    if (games.isEmpty()) {
                        return Optional.empty();
                    }
                    GameState g = games.getFirst();
                    var rows =
                            jdbc.query(
                                    """
                    SELECT s.*, r.provider_id AS release_provider_id,
                           pxr.provider_id AS platform_provider_id,
                           rxr.provider_id AS region_provider_id
                    FROM catalogue.release_snapshot s
                    JOIN catalogue.release_external_reference r
                      ON r.release_id=s.release_id AND r.game_id=s.game_id
                    LEFT JOIN catalogue.platform_external_reference pxr
                      ON pxr.platform_id=s.platform_id AND pxr.provider=:provider
                    LEFT JOIN catalogue.region_external_reference rxr
                      ON rxr.region_id=s.region_id AND rxr.provider=:provider
                    WHERE s.game_id=:game AND r.provider=:provider ORDER BY s.release_id LIMIT :limit
                    """,
                                    Map.of(
                                            "game",
                                            g.gameId(),
                                            "provider",
                                            provider,
                                            "limit",
                                            maxReleases + 1),
                                    (rs, row) ->
                                            Map.entry(
                                                    rs.getString("release_provider_id"),
                                                    new PublishedRelease(
                                                            rs.getObject("release_id", UUID.class),
                                                            g.gameId(),
                                                            rs.getString("platform_provider_id"),
                                                            rs.getString("region_provider_id"),
                                                            ReleaseDateRowMapper.map(rs),
                                                            ReleaseStatus.fromValue(
                                                                    rs.getString("release_status")),
                                                            instant(rs, "last_verified_at"),
                                                            VerificationLevel.fromValue(
                                                                    rs.getString(
                                                                            "verification_level")),
                                                            ReviewStatus.fromValue(
                                                                    rs.getString("review_status")),
                                                            ReleaseStage.fromValue(
                                                                    rs.getString(
                                                                            "release_stage")))));
                    if (rows.size() > maxReleases) {
                        throw new SynchronizationWriteException(
                                SynchronizationWriteException.Reason.RELEASE_BOUND_EXCEEDED,
                                SynchronizedGameIdentity.published(g.gameId(), g.slug()));
                    }
                    Map<String, PublishedRelease> releases = new LinkedHashMap<>();
                    rows.forEach(e -> releases.put(e.getKey(), e.getValue()));
                    return Optional.of(
                            new GameState(
                                    g.gameId(),
                                    g.title(),
                                    g.slug(),
                                    g.cover(),
                                    Map.copyOf(releases)));
                });
    }

    @Override
    public WriteResult saveGame(UUID runId, GameWrite write) {
        try {
            return transaction.execute(status -> save(runId, write, false));
        } catch (DataAccessException | TransactionException failure) {
            throw SynchronizationWriteFailures.classify(failure);
        }
    }

    @Override
    public WriteResult previewGame(GameWrite write) {
        try {
            return transaction.execute(
                    status -> {
                        WriteResult result = save(null, write, true);
                        status.setRollbackOnly();
                        return result;
                    });
        } catch (DataAccessException | TransactionException failure) {
            throw SynchronizationWriteFailures.classify(failure);
        }
    }

    private WriteResult save(UUID runId, GameWrite w, boolean dryRun) {
        if (!dryRun) {
            fence(runId);
        }
        // A short metadata lock serializes revision updates; no provider call holds this lock.
        jdbc.update(
                """
                INSERT INTO catalogue.catalogue_publication(publication_id,catalogue_version,published_at,
                    last_synchronized_at,source_kind,source_name,is_current)
                VALUES (:id,:version,:now,:now,'external_provider',:provider,true)
                ON CONFLICT DO NOTHING
                """,
                Map.of(
                        "id",
                        UUID.randomUUID(),
                        "version",
                        UUID.randomUUID().toString(),
                        "now",
                        timestamp(w.synchronizedAt()),
                        "provider",
                        provider));
        String publication =
                jdbc.queryForObject(
                        "SELECT publication_id::text FROM catalogue.catalogue_publication FOR UPDATE",
                        Map.of(),
                        String.class);
        MapSqlParameterSource snapshot =
                withCover(
                        new MapSqlParameterSource()
                                .addValue("publicationId", publication)
                                .addValue("gameId", w.gameId().toString())
                                .addValue("canonicalTitle", w.title())
                                .addValue("slug", w.slug()),
                        w.cover(),
                        provider);
        int gameChanges;
        if (w.creating()) {
            jdbc.update(
                    CatalogueSynchronizationSql.INSERT_GAME,
                    new MapSqlParameterSource()
                            .addValue("gameId", w.gameId().toString())
                            .addValue("createdAt", timestamp(w.synchronizedAt())));
            jdbc.update(
                    CatalogueSynchronizationSql.INSERT_EXTERNAL_REFERENCE,
                    new MapSqlParameterSource()
                            .addValue("gameId", w.gameId().toString())
                            .addValue("provider", provider)
                            .addValue("providerId", w.providerId())
                            .addValue("providerUrl", null));
            gameChanges = jdbc.update(CatalogueSynchronizationSql.INSERT_GAME_SNAPSHOT, snapshot);
        } else {
            gameChanges =
                    jdbc.update(
                            CatalogueSynchronizationSql.UPDATE_GAME_SNAPSHOT_WITH_COVER, snapshot);
        }
        // All writes use this lock order. Compute removals from the locked current state,
        // never from a stale application snapshot. The typed table is the release_date boundary.
        var missing =
                jdbc.query(
                        """
            SELECT s.release_id
            FROM catalogue.release_snapshot s
            JOIN catalogue.release_external_reference r ON r.release_id=s.release_id AND r.game_id=s.game_id
            WHERE s.game_id=:game AND r.provider=:provider
              AND s.source_kind='external_provider' AND s.source_name=:provider AND s.source_entity_type='release_date'
              AND NOT EXISTS (SELECT 1 FROM catalogue.release_external_reference other
                  WHERE other.game_id=s.game_id AND other.release_id=s.release_id AND other.provider<>:provider)
              AND r.provider_id NOT IN (:returned)
            ORDER BY s.release_id LIMIT :limit FOR UPDATE OF s,r
            """,
                        Map.of(
                                "game",
                                w.gameId(),
                                "provider",
                                provider,
                                "returned",
                                w.returnedReleaseReferences().isEmpty()
                                        ? List.of("")
                                        : w.returnedReleaseReferences(),
                                "limit",
                                w.maxReleases() + 1),
                        (rs, row) -> rs.getObject(1, UUID.class));
        if (missing.size() > w.maxReleases()) {
            throw new SynchronizationWriteException(
                    SynchronizationWriteException.Reason.RELEASE_BOUND_EXCEEDED);
        }
        // Remove before upsert: replacement evidence may legitimately reuse an obsolete tuple.
        // FK failures roll back this deletion and every create/update for this Game.
        if (!missing.isEmpty()) {
            var deletion = Map.of("ids", missing, "game", w.gameId(), "provider", provider);
            jdbc.update(
                    "DELETE FROM catalogue.release_snapshot WHERE game_id=:game AND release_id IN (:ids)",
                    deletion);
            jdbc.update(
                    "DELETE FROM catalogue.release_external_reference WHERE game_id=:game AND provider=:provider AND release_id IN (:ids)",
                    deletion);
            jdbc.update(
                    "DELETE FROM catalogue.game_release WHERE game_id=:game AND release_id IN (:ids)",
                    deletion);
        }
        int created = 0, updated = 0, unchanged = 0;
        for (ReleaseWrite value : w.releases()) {
            List<UUID> ids =
                    jdbc.query(
                            """
                    SELECT release_id FROM catalogue.release_external_reference
                    WHERE provider=:provider AND provider_id=:id AND game_id=:game
                    """,
                            Map.of(
                                    "provider",
                                    provider,
                                    "id",
                                    value.providerId(),
                                    "game",
                                    w.gameId()),
                            (rs, row) -> rs.getObject(1, UUID.class));
            boolean creating = ids.isEmpty();
            UUID releaseId = creating ? UUID.randomUUID() : ids.getFirst();
            if (creating) {
                jdbc.update(
                        CatalogueSynchronizationSql.INSERT_RELEASE_IDENTITY,
                        new MapSqlParameterSource()
                                .addValue("releaseId", releaseId.toString())
                                .addValue("gameId", w.gameId().toString())
                                .addValue("createdAt", timestamp(w.synchronizedAt())));
                jdbc.update(
                        """
                    INSERT INTO catalogue.release_external_reference(provider,provider_id,release_id,game_id)
                    VALUES(:provider,:id,:release,:game)
                    """,
                        Map.of(
                                "provider",
                                provider,
                                "id",
                                value.providerId(),
                                "release",
                                releaseId,
                                "game",
                                w.gameId()));
            }
            if (!creating
                    && !Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    """
                    SELECT s.source_kind='external_provider' AND s.source_name=:provider AND s.source_entity_type='release_date'
                        AND NOT EXISTS (SELECT 1 FROM catalogue.release_external_reference other
                            WHERE other.game_id=s.game_id AND other.release_id=s.release_id AND other.provider<>:provider)
                    FROM catalogue.release_snapshot s WHERE s.release_id=:release AND s.game_id=:game FOR UPDATE
                    """,
                                    Map.of(
                                            "provider",
                                            provider,
                                            "release",
                                            releaseId,
                                            "game",
                                            w.gameId()),
                                    Boolean.class))) {
                unchanged++;
                continue;
            }
            UUID platformId = resolvePlatform(value.release().platform());
            UUID regionId = resolveRegion(value.release().region());
            int changed =
                    jdbc.update(
                            CatalogueSynchronizationSql.INSERT_RELEASE_SNAPSHOT,
                            snapshotParameters(
                                    publication,
                                    value.release(),
                                    releaseId,
                                    w.gameId(),
                                    platformId,
                                    regionId));
            if (creating) {
                created++;
            } else if (changed > 0) {
                updated++;
            } else {
                unchanged++;
            }
        }
        writeFeaturedEvidence(w);
        writeFeaturedMedia(w);
        int detailChanges = writeDetails(publication, w);
        // Popularity and featured media serve featured discovery only, so they never advance the
        // catalogue revision; that read's validators hash its actual response instead. Game
        // details are catalogue content, so a detail change does.
        boolean changed =
                gameChanges > 0
                        || created > 0
                        || updated > 0
                        || !missing.isEmpty()
                        || detailChanges > 0;
        if (changed) {
            jdbc.update(
                    """
                    UPDATE catalogue.catalogue_publication SET catalogue_version=:version,
                        published_at=:now,last_synchronized_at=:now,source_kind='external_provider',source_name=:provider
                    WHERE publication_id=CAST(:id AS uuid)
                    """,
                    Map.of(
                            "version",
                            UUID.randomUUID().toString(),
                            "now",
                            timestamp(w.synchronizedAt()),
                            "provider",
                            provider,
                            "id",
                            publication));
        }
        if (!dryRun) {
            listingChanged.accept(w.gameId().toString());
        }
        return new WriteResult(
                w.creating(),
                !w.creating() && changed,
                created,
                updated,
                unchanged,
                missing.size());
    }

    /** Updates complete evidence in the Game transaction; mapping failure keeps its last valid state. */
    private void writeFeaturedEvidence(GameWrite w) {
        switch (w.featuredEvidence()) {
            case FeaturedEvidenceWrite.Observe observed ->
                    jdbc.update(
                            """
                    INSERT INTO catalogue.game_featured_evidence(
                        game_id, hypes, first_release_date, eligible_product, source_name, observed_at)
                    VALUES (:game, :hypes, :firstRelease, :eligible, :source, :observedAt)
                    ON CONFLICT (game_id) DO UPDATE SET
                        hypes = EXCLUDED.hypes,
                        first_release_date = EXCLUDED.first_release_date,
                        eligible_product = EXCLUDED.eligible_product,
                        source_name = EXCLUDED.source_name,
                        observed_at = EXCLUDED.observed_at
                    WHERE game_featured_evidence.observed_at <= EXCLUDED.observed_at
                    """,
                            new MapSqlParameterSource()
                                    .addValue("game", w.gameId())
                                    .addValue("hypes", observed.hypes().orElse(null))
                                    .addValue(
                                            "firstRelease",
                                            observed.firstReleaseDate().orElse(null))
                                    .addValue("eligible", observed.eligibleProduct())
                                    .addValue("source", provider)
                                    .addValue("observedAt", timestamp(w.synchronizedAt())));
            case FeaturedEvidenceWrite.Keep _ -> {}
        }
    }

    /**
     * Records a newer featured image or title logo inside the Game's own transaction, so a
     * rolled-back Game keeps its last valid selection. A kept slot is never touched: missing,
     * invalid or unavailable provider media never degrade the stored selection.
     */
    private void writeFeaturedMedia(GameWrite w) {
        writeMedia(w, "image", w.media().image());
        writeMedia(w, "card_image", w.media().cardImage());
        writeMedia(w, "logo", w.media().logo());
    }

    private void writeMedia(GameWrite w, String role, MediaWrite media) {
        if (!(media instanceof MediaWrite.Observe observed)) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO catalogue.game_featured_media(
                    game_id, media_role, media_kind, image_reference, width, height, transparent,
                    source_name, source_url, observed_at)
                VALUES (:game, :role, :kind, :reference, :width, :height, :transparent,
                        :source, :sourceUrl, :observedAt)
                ON CONFLICT (game_id, media_role) DO UPDATE SET
                    media_kind = EXCLUDED.media_kind,
                    image_reference = EXCLUDED.image_reference,
                    width = EXCLUDED.width,
                    height = EXCLUDED.height,
                    transparent = EXCLUDED.transparent,
                    source_name = EXCLUDED.source_name,
                    source_url = EXCLUDED.source_url,
                    observed_at = EXCLUDED.observed_at
                """,
                new MapSqlParameterSource()
                        .addValue("game", w.gameId())
                        .addValue("role", role)
                        .addValue("kind", observed.kind().name().toLowerCase(Locale.ROOT))
                        .addValue("reference", observed.reference())
                        .addValue("width", observed.width())
                        .addValue("height", observed.height())
                        .addValue("transparent", observed.transparent())
                        .addValue("source", provider)
                        .addValue("sourceUrl", observed.sourceUrl())
                        .addValue("observedAt", timestamp(w.synchronizedAt())));
    }

    /**
     * The product's explicit "no summary yet" editorial notice, installed as the summary default by
     * V20260906_120000. It is the stored representation of an absent summary, never provider text.
     */
    static final String NO_SUMMARY_TEXT = "Todavía no hay un resumen curado para este juego.";

    private static final String NO_SUMMARY_LANGUAGE = "es";

    /** Provider entity type recorded as summary provenance. */
    static final String SUMMARY_ENTITY_TYPE = "game_summary";

    /**
     * Replaces the Game's provider-acquired details with one complete valid answer, inside the Game
     * transaction, and returns how many stored rows changed. A kept answer touches nothing, so an
     * invalid or failed answer preserves the last valid details.
     */
    private int writeDetails(String publication, GameWrite w) {
        if (!(w.details() instanceof DetailsWrite.Observe(var details))) {
            return 0;
        }
        int changes = writeSummary(publication, w.gameId(), details.summary());
        changes += replaceCompanies(w.gameId(), "developer", details.developers());
        changes += replaceCompanies(w.gameId(), "publisher", details.publishers());
        changes +=
                replaceLinks(
                        "catalogue.game_genre",
                        "genre_id",
                        w.gameId(),
                        details.genres().stream().map(this::resolveGenre).toList());
        changes +=
                replaceLinks(
                        "catalogue.game_game_mode",
                        "game_mode_id",
                        w.gameId(),
                        details.gameModes().stream().map(this::resolveGameMode).toList());
        return changes;
    }

    /**
     * Records the provider summary, or the explicit no-summary notice when the provider states none.
     * Only an absent summary or one this provider wrote may change: product editorial text and
     * summaries of any other source stay outside synchronization ownership.
     */
    private int writeSummary(String publication, UUID gameId, Optional<ProviderSummary> summary) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("publication", UUID.fromString(publication))
                        .addValue("game", gameId)
                        .addValue("provider", provider)
                        .addValue("noSummaryText", NO_SUMMARY_TEXT)
                        .addValue("noSummaryLanguage", NO_SUMMARY_LANGUAGE);
        if (summary.isPresent()) {
            parameters
                    .addValue("kind", "sourced")
                    .addValue("text", summary.orElseThrow().text())
                    .addValue("language", summary.orElseThrow().language())
                    .addValue("sourceKind", "external_provider")
                    .addValue("sourceName", provider)
                    .addValue("entityType", SUMMARY_ENTITY_TYPE);
        } else {
            parameters
                    .addValue("kind", "editorial")
                    .addValue("text", NO_SUMMARY_TEXT)
                    .addValue("language", NO_SUMMARY_LANGUAGE)
                    .addValue("sourceKind", null)
                    .addValue("sourceName", null)
                    .addValue("entityType", null);
        }
        return jdbc.update(
                """
                UPDATE catalogue.game_snapshot
                SET summary_kind = CAST(:kind AS varchar),
                    summary_text = CAST(:text AS varchar),
                    summary_language = CAST(:language AS varchar),
                    summary_source_kind = CAST(:sourceKind AS varchar),
                    summary_source_name = CAST(:sourceName AS varchar),
                    summary_source_entity_type = CAST(:entityType AS varchar)
                WHERE publication_id = :publication AND game_id = :game
                  AND ((summary_kind = 'editorial' AND summary_text = :noSummaryText
                          AND summary_language = :noSummaryLanguage)
                       OR (summary_kind = 'sourced' AND summary_source_kind = 'external_provider'
                          AND summary_source_name = :provider))
                  AND (summary_kind, summary_text, summary_language, summary_source_kind,
                       summary_source_name, summary_source_entity_type)
                      IS DISTINCT FROM
                      (CAST(:kind AS varchar), CAST(:text AS varchar), CAST(:language AS varchar),
                       CAST(:sourceKind AS varchar), CAST(:sourceName AS varchar),
                       CAST(:entityType AS varchar))
                """,
                parameters);
    }

    private int replaceCompanies(UUID gameId, String role, List<ProviderCompany> companies) {
        int changes = 0;
        List<UUID> ids = new java.util.ArrayList<>();
        for (ProviderCompany company : companies) {
            Resolved resolved = resolveCompany(company);
            ids.add(resolved.id());
            changes += resolved.changes();
        }
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("game", gameId)
                        .addValue("role", role)
                        .addValue("ids", ids.isEmpty() ? List.of(new UUID(0, 0)) : ids);
        changes +=
                jdbc.update(
                        """
                DELETE FROM catalogue.game_company
                WHERE game_id = :game AND company_role = :role AND company_id NOT IN (:ids)
                """,
                        parameters);
        for (UUID id : ids) {
            changes +=
                    jdbc.update(
                            """
                    INSERT INTO catalogue.game_company (game_id, company_role, company_id)
                    VALUES (:game, :role, :id)
                    ON CONFLICT DO NOTHING
                    """,
                            Map.of("game", gameId, "role", role, "id", id));
        }
        return changes;
    }

    /** Makes the Game's links in {@code table} exactly {@code ids}; table and column are constants. */
    private int replaceLinks(String table, String column, UUID gameId, List<Resolved> resolved) {
        int changes = resolved.stream().mapToInt(Resolved::changes).sum();
        List<UUID> ids = resolved.stream().map(Resolved::id).toList();
        changes +=
                jdbc.update(
                        "DELETE FROM "
                                + table
                                + " WHERE game_id = :game AND "
                                + column
                                + " NOT IN (:ids)",
                        Map.of(
                                "game",
                                gameId,
                                "ids",
                                ids.isEmpty() ? List.of(new UUID(0, 0)) : ids));
        for (UUID id : ids) {
            changes +=
                    jdbc.update(
                            "INSERT INTO "
                                    + table
                                    + " (game_id, "
                                    + column
                                    + ") VALUES (:game, :id) ON CONFLICT DO NOTHING",
                            Map.of("game", gameId, "id", id));
        }
        return changes;
    }

    /** A resolved product identity and the stored rows its resolution changed. */
    private record Resolved(UUID id, int changes) {}

    /**
     * Resolves a provider company to product identity through its typed reference, creating the
     * company on first sight. Its name is provider evidence, so a known company follows the latest
     * valid name; names never resolve or merge companies.
     */
    private Resolved resolveCompany(ProviderCompany company) {
        List<UUID> existing =
                jdbc.query(
                        "SELECT company_id FROM catalogue.company_external_reference"
                                + " WHERE provider=:provider AND provider_id=:id",
                        Map.of("provider", provider, "id", company.providerId()),
                        (rs, row) -> rs.getObject(1, UUID.class));
        if (!existing.isEmpty()) {
            UUID id = existing.getFirst();
            int renamed =
                    jdbc.update(
                            "UPDATE catalogue.company SET display_name=:name"
                                    + " WHERE company_id=:id AND display_name<>:name",
                            Map.of("id", id, "name", company.name()));
            return new Resolved(id, renamed);
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO catalogue.company(company_id, display_name) VALUES(:id, :name)",
                Map.of("id", id, "name", company.name()));
        jdbc.update(
                "INSERT INTO catalogue.company_external_reference(provider, provider_id, company_id)"
                        + " VALUES(:provider, :pid, :id)",
                Map.of("provider", provider, "pid", company.providerId(), "id", id));
        return new Resolved(id, 1);
    }

    private Resolved resolveGenre(ProviderTerm genre) {
        return resolveTerm("catalogue.genre", "genre_id", "genre", genre);
    }

    private Resolved resolveGameMode(ProviderTerm mode) {
        return resolveTerm("catalogue.game_mode", "game_mode_id", "game-mode", mode);
    }

    /**
     * Resolves a provider genre or game mode like platform taxonomy (ADR-0020): the typed reference
     * is identity; a new product entity takes a readable code and display name from the provider's
     * descriptors; its Spanish label and separate source wording follow localization ownership. {@code table},
     * {@code column} and {@code kind} are constants.
     */
    private Resolved resolveTerm(String table, String column, String kind, ProviderTerm term) {
        String references = table + "_external_reference";
        List<UUID> existing =
                jdbc.query(
                        "SELECT "
                                + column
                                + " FROM "
                                + references
                                + " WHERE provider=:provider AND provider_id=:id",
                        Map.of("provider", provider, "id", term.providerId()),
                        (rs, row) -> rs.getObject(1, UUID.class));
        if (!existing.isEmpty()) {
            return new Resolved(
                    existing.getFirst(),
                    localizeTermSource(table, column, existing.getFirst(), term));
        }
        UUID id = UUID.randomUUID();
        String descriptor =
                term.slug() == null || term.slug().isBlank() ? term.name() : term.slug();
        String base = slugify(descriptor);
        if (base.length() > MAX_TERM_CODE_BASE) {
            base = base.substring(0, MAX_TERM_CODE_BASE).replaceAll("-+$", "");
        }
        if (base.isEmpty()) {
            base = kind + "-" + term.providerId();
        }
        String code =
                codeExists(table, base)
                        ? (codeExists(table, base + "-" + term.providerId())
                                ? kind + "-" + term.providerId()
                                : base + "-" + term.providerId())
                        : base;
        jdbc.update(
                "INSERT INTO "
                        + table
                        + "("
                        + column
                        + ", code, display_name) VALUES(:id, :code, :name)",
                Map.of("id", id, "code", code, "name", term.name()));
        jdbc.update(
                "INSERT INTO "
                        + references
                        + "(provider, provider_id, "
                        + column
                        + ") VALUES(:provider, :pid, :id)",
                Map.of("provider", provider, "pid", term.providerId(), "id", id));
        return new Resolved(id, 1 + localizeTermSource(table, column, id, term));
    }

    private int localizeTermSource(String table, String column, UUID id, ProviderTerm term) {
        var labels =
                jdbc.query(
                        "SELECT spanish_label FROM catalogue.curated_taxonomy_label"
                                + " WHERE taxonomy=:kind AND provider=:provider AND provider_id=:pid",
                        Map.of(
                                "kind",
                                table.substring("catalogue.".length()),
                                "provider",
                                provider,
                                "pid",
                                term.providerId()),
                        (rs, row) -> rs.getString(1));
        var parameters =
                new MapSqlParameterSource().addValue("id", id).addValue("source", term.name());
        String assignments = "source_label=:source";
        String comparison = "source_label IS DISTINCT FROM :source";
        if (!labels.isEmpty()) {
            parameters.addValue("label", labels.getFirst());
            assignments +=
                    ", display_name=:label, label_origin='curated', translation_fingerprint=NULL";
            comparison += " OR display_name IS DISTINCT FROM :label OR label_origin <> 'curated'";
        } else {
            // Until first valid translation, the explicit serving fallback tracks the source.
            assignments +=
                    ", display_name=CASE WHEN label_origin='source' THEN :source ELSE display_name END";
        }
        int changed =
                jdbc.update(
                        "UPDATE "
                                + table
                                + " SET "
                                + assignments
                                + " WHERE "
                                + column
                                + "=:id AND ("
                                + comparison
                                + ")",
                        parameters);
        if (changed > 0 && table.equals("catalogue.genre")) {
            var productTerm =
                    jdbc.queryForObject(
                            "SELECT code,display_name FROM catalogue.genre WHERE genre_id=:id",
                            Map.of("id", id),
                            (rs, row) ->
                                    new com.videogameplatform.catalogue.application.details
                                            .GameDetailsResult.Term(
                                            rs.getString(1), rs.getString(2)));
            genreLabelChanged.accept(productTerm);
        }
        return changed;
    }

    /** Leaves room for a provider-reference suffix inside the 100-character code column. */
    private static final int MAX_TERM_CODE_BASE = 60;

    /** Product-only sentinel for a release whose provider states no region; it has no reference. */
    private static final String UNKNOWN_REGION_CODE = "unknown";

    /**
     * Resolves a provider platform to product identity, reusing the known reference or creating the
     * product platform and its reference as part of this accepted write. Identity is the provider
     * reference; the slug and name only seed a readable code and display name, never identity.
     */
    private UUID resolvePlatform(ProviderPlatform platform) {
        List<UUID> existing =
                jdbc.query(
                        "SELECT platform_id FROM catalogue.platform_external_reference"
                                + " WHERE provider=:provider AND provider_id=:id",
                        Map.of("provider", provider, "id", platform.providerId()),
                        (rs, row) -> rs.getObject(1, UUID.class));
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO catalogue.platform(platform_id, code, display_name)"
                        + " VALUES(CAST(:id AS uuid), :code, :name)",
                Map.of(
                        "id",
                        id.toString(),
                        "code",
                        uniqueCode("catalogue.platform", "platform", platform.slug(), platform),
                        "name",
                        displayName(platform.name(), platform.slug(), "platform", platform)));
        jdbc.update(
                "INSERT INTO catalogue.platform_external_reference(provider, provider_id, platform_id)"
                        + " VALUES(:provider, :pid, CAST(:id AS uuid))",
                Map.of("provider", provider, "pid", platform.providerId(), "id", id.toString()));
        return id;
    }

    /**
     * Resolves a provider release region to product identity. An absent region maps to the product
     * 'unknown' sentinel; a present one reuses or creates the product region and its reference. A
     * created region reads its product label (CAT-008), never the raw provider descriptor, and a
     * reused one keeps its label whatever the provider now calls it.
     */
    private UUID resolveRegion(Optional<ProviderRegion> region) {
        if (region.isEmpty()) {
            return jdbc.query(
                            "SELECT region_id FROM catalogue.region WHERE code=:code",
                            Map.of("code", UNKNOWN_REGION_CODE),
                            (rs, row) -> rs.getObject(1, UUID.class))
                    .getFirst();
        }
        ProviderRegion value = region.orElseThrow();
        List<UUID> existing =
                jdbc.query(
                        "SELECT region_id FROM catalogue.region_external_reference"
                                + " WHERE provider=:provider AND provider_id=:id",
                        Map.of("provider", provider, "id", value.providerId()),
                        (rs, row) -> rs.getObject(1, UUID.class));
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO catalogue.region(region_id, code, display_name)"
                        + " VALUES(CAST(:id AS uuid), :code, :name)",
                Map.of(
                        "id",
                        id.toString(),
                        "code",
                        uniqueCode("catalogue.region", "region", value.name(), value),
                        "name",
                        RegionLabel.fromDescriptor(value.name()).value()));
        jdbc.update(
                "INSERT INTO catalogue.region_external_reference(provider, provider_id, region_id)"
                        + " VALUES(:provider, :pid, CAST(:id AS uuid))",
                Map.of("provider", provider, "pid", value.providerId(), "id", id.toString()));
        return id;
    }

    private String uniqueCode(String table, String kind, String descriptor, Object reference) {
        String base = slugify(descriptor);
        if (base.isEmpty()) {
            base = kind + "-" + providerReference(reference);
        }
        if (!codeExists(table, base)) {
            return base;
        }
        // A readable slug can collide with an unrelated product entity; disambiguate
        // deterministically
        // with the provider reference, which is unique per provider. Identity never depends on
        // this.
        String disambiguated = base + "-" + slugify(providerReference(reference));
        return codeExists(table, disambiguated)
                ? kind + "-" + slugify(providerReference(reference))
                : disambiguated;
    }

    private boolean codeExists(String table, String code) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE code=:code",
                        Map.of("code", code),
                        Integer.class);
        return count != null && count > 0;
    }

    private static String displayName(String name, String slug, String kind, Object reference) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (slug != null && !slug.isBlank()) {
            return slug.trim();
        }
        return kind + " " + providerReference(reference);
    }

    private static String providerReference(Object reference) {
        return switch (reference) {
            case ProviderPlatform platform -> platform.providerId();
            case ProviderRegion region -> region.providerId();
            default -> "";
        };
    }

    /** Normalizes descriptive text into a valid taxonomy code; identity is the provider reference. */
    private static String slugify(String value) {
        if (value == null) {
            return "";
        }
        String slug =
                value.trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+", "")
                        .replaceAll("-+$", "");
        return slug;
    }

    @Override
    public void completeRun(CatalogueSynchronizationReport report, int retainedRuns) {
        transaction.executeWithoutResult(
                status -> {
                    fence(report.runId());
                    jdbc.update(
                            """
                    UPDATE catalogue.synchronization_run SET completed_at=:completed,
                        run_status=:status,outcome_code=:code,report=CAST(:report AS jsonb)
                    WHERE run_id=:id
                    """,
                            Map.of(
                                    "completed",
                                    timestamp(report.completedAt()),
                                    "status",
                                    report.outcome().name().toLowerCase(Locale.ROOT),
                                    "code",
                                    report.outcomeCode(),
                                    "report",
                                    json.writeValueAsString(report),
                                    "id",
                                    report.runId()));
                    jdbc.update(
                            """
                    DELETE FROM catalogue.synchronization_run WHERE run_id IN
                      (SELECT run_id FROM catalogue.synchronization_run WHERE provider=:provider AND run_status<>'running'
                       ORDER BY started_at DESC,run_id DESC OFFSET :retained)
                    """,
                            Map.of("provider", provider, "retained", retainedRuns));
                });
    }

    @Override
    public Optional<CatalogueSynchronizationReport> lastRun() {
        return jdbc.query(
                """
                SELECT * FROM catalogue.synchronization_run WHERE provider=:provider
                ORDER BY started_at DESC,run_id DESC LIMIT 1
                """,
                Map.of("provider", provider),
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    if (rs.getString("report") != null) {
                        return Optional.of(readReport(rs.getString("report")));
                    }
                    return Optional.of(
                            new CatalogueSynchronizationReport(
                                    rs.getObject("run_id", UUID.class),
                                    rs.getObject("window_from", java.time.LocalDate.class),
                                    rs.getObject("window_to", java.time.LocalDate.class),
                                    instant(rs, "started_at"),
                                    instant(rs, "completed_at"),
                                    "running".equals(rs.getString("run_status"))
                                            ? SynchronizationOutcome.RUNNING
                                            : SynchronizationOutcome.FAILED,
                                    rs.getString("outcome_code"),
                                    new CatalogueSynchronizationReport.Counters(
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0)));
                });
    }

    private CatalogueSynchronizationReport readReport(String stored) {
        var tree = json.readTree(stored);
        // Reports written before current-set reconciliation have no deletion counter, and those
        // written before popularity and media acquisition have no counters for them. Default only
        // those absent fields; keep strict validation of all existing data.
        if (tree.get("counters") instanceof tools.jackson.databind.node.ObjectNode counters) {
            for (String field :
                    List.of(
                            "deletedReleases",
                            "popularityObservedGames",
                            "popularityClearedGames",
                            "popularityUnavailableGames",
                            "featuredImageObservedGames",
                            "logoObservedGames",
                            "logoUnavailableGames",
                            "detailsUnavailableGames")) {
                if (!counters.has(field)) {
                    counters.put(field, 0L);
                }
            }
        }
        return json.treeToValue(tree, CatalogueSynchronizationReport.class);
    }

    private static MapSqlParameterSource withCover(
            MapSqlParameterSource parameters, CoverSelection cover, String providerName) {
        return switch (cover) {
            case CoverSelection.Provider providerCover ->
                    parameters
                            .addValue("coverReference", providerCover.reference())
                            .addValue("coverSource", providerName)
                            .addValue("coverUsageMode", PROVIDER_COVER)
                            .addValue("coverAlternativeText", providerCover.alternativeText())
                            .addValue("coverSourceUrl", providerCover.sourceUrl());
            case CoverSelection.ProductFallback fallback ->
                    parameters
                            .addValue("coverReference", fallback.assetPath())
                            .addValue("coverSource", fallback.sourceName())
                            .addValue("coverUsageMode", PRODUCT_OWNED_COVER)
                            .addValue("coverAlternativeText", fallback.alternativeText())
                            .addValue("coverSourceUrl", null);
        };
    }

    private static SqlParameterSource snapshotParameters(
            String publicationId,
            PlannedRelease release,
            UUID releaseId,
            UUID gameId,
            UUID platformId,
            UUID regionId) {
        ReleaseDate date = release.date();
        return new MapSqlParameterSource()
                .addValue("publicationId", publicationId)
                .addValue("releaseId", releaseId.toString())
                .addValue("gameId", gameId.toString())
                .addValue("platformId", platformId.toString())
                .addValue("regionId", regionId.toString())
                .addValue("datePrecision", date.precision().value())
                .addValue("exactDate", date instanceof ReleaseDate.Day day ? day.date() : null)
                .addValue("releaseYear", releaseYear(date))
                .addValue(
                        "releaseMonth",
                        date instanceof ReleaseDate.Month month
                                ? month.month().getMonthValue()
                                : null)
                .addValue(
                        "releaseQuarter",
                        date instanceof ReleaseDate.Quarter quarter ? quarter.quarter() : null)
                .addValue("releaseStatus", release.status().value())
                .addValue("releaseStage", release.stage().value())
                .addValue("sourceKind", release.sourceKind().value())
                .addValue("sourceName", release.sourceName())
                .addValue("sourceEntityType", release.sourceEntityType())
                .addValue("providerUpdatedAt", timestamp(release.providerUpdatedAt()))
                .addValue("lastSynchronizedAt", timestamp(release.lastSynchronizedAt()))
                .addValue("lastVerifiedAt", timestamp(release.lastVerifiedAt()))
                .addValue("verificationLevel", release.verificationLevel().value())
                .addValue("reviewStatus", release.reviewStatus().value());
    }

    private static Integer releaseYear(ReleaseDate date) {
        return switch (date) {
            case ReleaseDate.Month month -> month.month().getYear();
            case ReleaseDate.Quarter quarter -> quarter.year();
            case ReleaseDate.YearOnly year -> year.year().getValue();
            case ReleaseDate other -> null;
        };
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
