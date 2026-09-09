package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.domain.ReleaseDate;
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
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcCatalogueSynchronizationStore(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction, String provider) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.provider = provider;
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
            throw new SynchronizationWriteException("Synchronization ownership expired");
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
    public CatalogueContext loadContext() {
        return new CatalogueContext(
                taxonomy(CatalogueSynchronizationSql.PLATFORM_CODES),
                taxonomy(CatalogueSynchronizationSql.REGION_CODES));
    }

    private Map<String, UUID> taxonomy(String sql) {
        Map<String, UUID> values = new LinkedHashMap<>();
        jdbc.query(
                sql,
                Map.of(),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> values.put(rs.getString(1), UUID.fromString(rs.getString(2))));
        return Map.copyOf(values);
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
                    SELECT s.*, r.provider_id FROM catalogue.release_snapshot s
                    JOIN catalogue.release_external_reference r ON r.release_id=s.release_id AND r.game_id=s.game_id
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
                                                    rs.getString("provider_id"),
                                                    new PublishedRelease(
                                                            rs.getObject("release_id", UUID.class),
                                                            new ReleaseIdentity(
                                                                    g.gameId(),
                                                                    rs.getObject(
                                                                            "platform_id",
                                                                            UUID.class),
                                                                    rs.getObject(
                                                                            "region_id",
                                                                            UUID.class)),
                                                            ReleaseDateRowMapper.map(rs),
                                                            ReleaseStatus.fromValue(
                                                                    rs.getString("release_status")),
                                                            instant(rs, "last_verified_at"),
                                                            VerificationLevel.fromValue(
                                                                    rs.getString(
                                                                            "verification_level")),
                                                            ReviewStatus.fromValue(
                                                                    rs.getString(
                                                                            "review_status")))));
                    if (rows.size() > maxReleases) {
                        throw new SynchronizationWriteException("Game exceeds release bound");
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
            return transaction.execute(status -> save(runId, write));
        } catch (DataAccessException | TransactionException failure) {
            throw new SynchronizationWriteException(failure);
        }
    }

    private WriteResult save(UUID runId, GameWrite w) {
        fence(runId);
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
            int changed =
                    jdbc.update(
                            CatalogueSynchronizationSql.INSERT_RELEASE_SNAPSHOT,
                            snapshotParameters(publication, value.release(), releaseId));
            if (creating) {
                created++;
            } else if (changed > 0) {
                updated++;
            } else {
                unchanged++;
            }
        }
        boolean changed = gameChanges > 0 || created > 0 || updated > 0;
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
        return new WriteResult(w.creating(), !w.creating() && changed, created, updated, unchanged);
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
                        return Optional.of(
                                json.readValue(
                                        rs.getString("report"),
                                        CatalogueSynchronizationReport.class));
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
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
                });
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
            String publicationId, PlannedRelease release, UUID releaseId) {
        ReleaseDate date = release.date();
        return new MapSqlParameterSource()
                .addValue("publicationId", publicationId)
                .addValue("releaseId", releaseId.toString())
                .addValue("gameId", release.identity().gameId().toString())
                .addValue("platformId", release.identity().platformId().toString())
                .addValue("regionId", release.identity().regionId().toString())
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
