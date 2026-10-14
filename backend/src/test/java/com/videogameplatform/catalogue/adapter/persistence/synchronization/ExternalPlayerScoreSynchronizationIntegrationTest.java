package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlayerScore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.domain.ExternalScore;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL evidence for #255 phase 1: synchronization acquires the external player score of each
 * Game as Catalogue-owned current state keyed by internal identity. A complete absence clears the
 * score but records the check; malformed evidence, unattributable scores and failed Games keep the
 * last valid score and its last check; an unchanged re-check is distinct from a content change.
 */
class ExternalPlayerScoreSynchronizationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private static final Instant RECORD_UPDATED = Instant.parse("2026-10-01T10:00:00Z");
    private static final CatalogueSynchronizationRequest WINDOW =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
    private static final String ORIGINAL_PAGE = "https://www.igdb.com/games/resident-evil-2--1";
    private static final String REMAKE_PAGE = "https://www.igdb.com/games/resident-evil-2";

    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;
    private FixtureProvider provider;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("scores");
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var ds =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(database),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbc = new JdbcTemplate(ds);
        store =
                new JdbcCatalogueSynchronizationStore(
                        new NamedParameterJdbcTemplate(jdbc),
                        new TransactionTemplate(new JdbcTransactionManager(ds)),
                        "IGDB",
                        gameId -> {});
        provider = new FixtureProvider();
    }

    @Test
    void storesEachScoreAgainstItsOwnInternalGameSoAnOriginalAndItsRemakeNeverShareOne() {
        provider.put(
                work(
                        "100",
                        ProviderWorkType.MAIN_GAME,
                        ORIGINAL_PAGE,
                        scored("83.67289380364453", 604),
                        release("10")));
        provider.put(
                work(
                        "200",
                        ProviderWorkType.REMAKE,
                        REMAKE_PAGE,
                        scored("87.4", 1434),
                        release("20")));

        var report = synchronizeAt(NOW);

        assertThat(report.counters().playerScoreObservedGames()).isEqualTo(2);
        assertThat(score("100"))
                .containsEntry("score", new BigDecimal("83.672894"))
                .containsEntry("score_count", 604L)
                .containsEntry("source_name", "IGDB")
                .containsEntry("score_kind", "player")
                .containsEntry("source_url", ORIGINAL_PAGE)
                .containsEntry("provider_record_updated_at", RECORD_UPDATED)
                .containsEntry("observed_at", NOW)
                .containsEntry("changed_at", NOW);
        assertThat(score("200"))
                .containsEntry("score", new BigDecimal("87.400000"))
                .containsEntry("score_count", 1434L)
                .containsEntry("source_url", REMAKE_PAGE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT game_id) FROM catalogue.game_external_score",
                                Long.class))
                .isEqualTo(2);
        // Provider identity stays in the external references: the score row has none.
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_schema='catalogue'"
                                        + " AND table_name='game_external_score'",
                                String.class))
                .noneMatch(column -> column.contains("provider_id"));
    }

    @Test
    void anUnchangedRecheckAdvancesOnlyTheCheckAndNeverTheRevisionOrMovesBackwards() {
        provider.put(work("100", scored("80", 9)));
        synchronizeAt(NOW);
        String revision = version();

        Instant recheck = NOW.plus(Duration.ofDays(8));
        var unchanged = synchronizeAt(recheck);

        assertThat(unchanged.counters().unchangedGames()).isEqualTo(1);
        assertThat(score("100"))
                .containsEntry("score", new BigDecimal("80.000000"))
                .containsEntry("observed_at", recheck)
                .containsEntry("changed_at", NOW);
        assertThat(version()).isEqualTo(revision);

        Instant changedAt = recheck.plus(Duration.ofDays(1));
        provider.put(work("100", scored("80", 10)));
        synchronizeAt(changedAt);
        assertThat(score("100"))
                .containsEntry("score_count", 10L)
                .containsEntry("observed_at", changedAt)
                .containsEntry("changed_at", changedAt);
        // The score has no public reader yet, so its change leaves the revision alone.
        assertThat(version()).isEqualTo(revision);

        // An earlier or replayed answer never moves the stored check or score backwards.
        provider.put(work("100", scored("50", 2)));
        synchronizeAt(recheck);
        assertThat(score("100"))
                .containsEntry("score_count", 10L)
                .containsEntry("observed_at", changedAt);
    }

    @Test
    void aCompleteAbsenceOrZeroRatingsClearsTheScoreButRecordsTheCheckAndKeepsTheGame() {
        provider.put(work("100", scored("75.5", 40)));
        synchronizeAt(NOW);

        Instant cleared = NOW.plusSeconds(60);
        provider.put(work("100", Optional.of(ProviderPlayerScore.NONE)));
        var report = synchronizeAt(cleared);

        assertThat(report.counters().playerScoreAbsentGames()).isEqualTo(1);
        assertThat(score("100"))
                .containsEntry("score", null)
                .containsEntry("score_count", null)
                .containsEntry("observed_at", cleared)
                .containsEntry("changed_at", cleared);
        assertThat(count("game")).isEqualTo(1);
        assertThat(count("game_release")).isEqualTo(1);

        // A later valid score returns as new content.
        Instant restored = cleared.plusSeconds(60);
        provider.put(work("100", scored("76", 41)));
        synchronizeAt(restored);
        assertThat(score("100"))
                .containsEntry("score_count", 41L)
                .containsEntry("changed_at", restored);
    }

    @Test
    void malformedOrUnattributableEvidenceKeepsTheLastValidScoreAndItsLastCheck() {
        provider.put(work("100", scored("75.5", 40)));
        synchronizeAt(NOW);
        Map<String, Object> valid = score("100");

        provider.put(work("100", Optional.empty()));
        var malformed = synchronizeAt(NOW.plusSeconds(60));
        provider.put(work("100", ProviderWorkType.MAIN_GAME, null, scored("10", 1), release("10")));
        var unattributed = synchronizeAt(NOW.plusSeconds(120));

        assertThat(malformed.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(malformed.counters().playerScoreUnavailableGames()).isEqualTo(1);
        assertThat(unattributed.counters().playerScoreUnavailableGames()).isEqualTo(1);
        assertThat(score("100")).isEqualTo(valid);
    }

    @Test
    void aFailedGameRollsBackItsScoreWithEverythingElseOfThatGame() {
        provider.put(work("100", scored("75.5", 40)));
        synchronizeAt(NOW);
        Map<String, Object> valid = score("100");

        // Two references collide on one durable release tuple, so the whole Game write rolls back.
        provider.put(
                work(
                        "100",
                        ProviderWorkType.MAIN_GAME,
                        ORIGINAL_PAGE,
                        scored("99", 999),
                        release("10"),
                        release("11")));
        var failedWrite = synchronizeAt(NOW.plusSeconds(60));
        provider.failure =
                new ProviderRequestException(
                        ProviderFailureCode.PROVIDER_UNAVAILABLE, ProviderCallStatistics.none());
        var failedFetch = synchronizeAt(NOW.plusSeconds(120));

        assertThat(failedWrite.counters().failedGames()).isEqualTo(1);
        assertThat(failedWrite.counters().playerScoreObservedGames()).isZero();
        assertThat(failedFetch.counters().failedGames()).isEqualTo(1);
        assertThat(score("100")).isEqualTo(valid);
    }

    @Test
    void aGameWithoutUsableEvidenceHasNoRowRatherThanAnInventedAbsence() {
        provider.put(work("100", Optional.empty()));

        synchronizeAt(NOW);

        assertThat(count("game")).isEqualTo(1);
        assertThat(count("game_external_score")).isZero();
    }

    @Test
    void repairRefreshesAKnownGameScoreAndItsPreviewWritesNothing() {
        provider.put(work("100", Optional.empty()));
        synchronizeAt(NOW);
        provider.put(work("100", scored("64", 12)));

        var preview = service(NOW.plusSeconds(60)).repairGames(List.of("100"), true);
        assertThat(preview.counters().playerScoreObservedGames()).isEqualTo(1);
        assertThat(count("game_external_score")).isZero();

        service(NOW.plusSeconds(120)).repairGames(List.of("100"), false);
        assertThat(score("100"))
                .containsEntry("score_count", 12L)
                .containsEntry("observed_at", NOW.plusSeconds(120));
    }

    @Test
    void countsWhatEachRunDidToTheStoredScoresFromTheWritingStatementItself() {
        provider.put(work("100", scored("80", 9)));
        provider.put(work("200", Optional.of(ProviderPlayerScore.NONE)));
        provider.put(work("300", Optional.empty()));
        var first = synchronizeAt(NOW);
        assertThat(scoreCounters(first)).containsExactly(1L, 1L, 1L, 1L, 0L, 0L, 0L);

        provider.put(work("200", scored("70", 2)));
        provider.put(work("300", scored("60", 4)));
        var second = synchronizeAt(NOW.plusSeconds(60));
        assertThat(scoreCounters(second)).containsExactly(3L, 0L, 0L, 2L, 0L, 0L, 1L);

        provider.put(work("100", scored("81", 10)));
        provider.put(work("200", Optional.of(ProviderPlayerScore.NONE)));
        provider.put(work("300", Optional.empty()));
        var third = synchronizeAt(NOW.plusSeconds(120));
        assertThat(scoreCounters(third)).containsExactly(1L, 1L, 1L, 0L, 1L, 1L, 0L);

        // An answer older than the stored check writes nothing and changes no score.
        provider.put(work("100", scored("50", 1)));
        var replayed = synchronizeAt(NOW.plusSeconds(90));
        assertThat(scoreCounters(replayed)).containsExactly(1L, 1L, 1L, 0L, 0L, 0L, 0L);
        assertThat(score("100")).containsEntry("score_count", 10L);

        // A still-absent score is no score change; repeating the run changes nothing.
        provider.put(work("100", scored("81", 10)));
        var repeated = synchronizeAt(NOW.plusSeconds(180));
        assertThat(scoreCounters(repeated)).containsExactly(1L, 1L, 1L, 0L, 0L, 0L, 1L);
    }

    @Test
    void anyAttributionTheApplicationAcceptsIsStoredSoAScoreNeverFailsItsGame() {
        String page = "https://www.igdb.com/games/some-game/?from=search";
        provider.put(work("100", ProviderWorkType.MAIN_GAME, page, scored("80", 9), release("10")));

        var report = synchronizeAt(NOW);

        assertThat(report.counters().failedGames()).isZero();
        assertThat(score("100")).containsEntry("source_url", page);
    }

    @Test
    void aPreviewPredictsTheScoreChangeWithoutWritingIt() {
        provider.put(work("100", scored("80", 9)));
        synchronizeAt(NOW);
        Map<String, Object> stored = score("100");
        provider.put(work("100", scored("82", 11)));

        var preview = service(NOW.plusSeconds(60)).repairGames(List.of("100"), true);

        assertThat(preview.counters().playerScoreUpdatedGames()).isEqualTo(1);
        assertThat(score("100")).isEqualTo(stored);
    }

    /** Observed, absent, unavailable, created, updated, removed, unchanged. */
    private static List<Long> scoreCounters(CatalogueSynchronizationReport report) {
        var c = report.counters();
        return List.of(
                c.playerScoreObservedGames(),
                c.playerScoreAbsentGames(),
                c.playerScoreUnavailableGames(),
                c.playerScoreCreatedGames(),
                c.playerScoreUpdatedGames(),
                c.playerScoreRemovedGames(),
                c.playerScoreUnchangedGames());
    }

    @Test
    void theDatabaseRejectsAnInvalidUnattributedOrUnapprovedScore() {
        provider.put(work("100", Optional.empty()));
        synchronizeAt(NOW);
        String game = gameId("100");
        String insert =
                "INSERT INTO catalogue.game_external_score(game_id, source_name, score_kind, score,"
                        + " score_count, source_url, observed_at, changed_at)"
                        + " VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?, ?, ?)";
        var now = java.sql.Timestamp.from(NOW);
        var later = java.sql.Timestamp.from(NOW.plusSeconds(1));
        for (Object[] invalid :
                List.of(
                        new Object[] {"IGDB", "player", 100.5, 3, ORIGINAL_PAGE, now, now},
                        new Object[] {"IGDB", "player", -1, 3, ORIGINAL_PAGE, now, now},
                        new Object[] {"IGDB", "player", 80, 0, ORIGINAL_PAGE, now, now},
                        new Object[] {"IGDB", "player", 80, null, ORIGINAL_PAGE, now, now},
                        new Object[] {"IGDB", "player", null, 3, ORIGINAL_PAGE, now, now},
                        new Object[] {"IGDB", "player", 80, 3, null, now, now},
                        new Object[] {
                            "IGDB", "player", 80, 3, "https://example.com/games/x", now, now
                        },
                        new Object[] {"IGDB", "critic", 80, 3, ORIGINAL_PAGE, now, now},
                        new Object[] {"IGDB", "total", 80, 3, ORIGINAL_PAGE, now, now},
                        new Object[] {"OpenCritic", "player", 80, 3, ORIGINAL_PAGE, now, now},
                        new Object[] {"IGDB", "player", 80, 3, ORIGINAL_PAGE, now, later})) {
            Object[] arguments = new Object[invalid.length + 1];
            arguments[0] = game;
            System.arraycopy(invalid, 0, arguments, 1, invalid.length);
            assertThatThrownBy(() -> jdbc.update(insert, arguments))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        "00000000-0000-4000-8000-000000000001",
                                        "IGDB",
                                        "player",
                                        80,
                                        3,
                                        ORIGINAL_PAGE,
                                        now,
                                        now))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A stated absence needs no attribution; one current row per Game, source and kind.
        jdbc.update(insert, game, "IGDB", "player", null, null, null, now, now);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        game,
                                        "IGDB",
                                        "player",
                                        80,
                                        3,
                                        ORIGINAL_PAGE,
                                        now,
                                        now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CatalogueSynchronizationReport synchronizeAt(Instant instant) {
        return service(instant).synchronize(WINDOW);
    }

    private CatalogueSynchronizationService service(Instant instant) {
        return new CatalogueSynchronizationService(
                store,
                provider,
                Clock.fixed(instant, ZoneId.of("Europe/Madrid")),
                new SynchronizationPolicy(10, 25, 50, Duration.ofMinutes(30)),
                new CoverSelectionPolicy("/assets/covers/fallback.svg", "VideoGame Platform"),
                SynchronizationProgress.NONE);
    }

    private static Optional<ProviderPlayerScore> scored(String value, long count) {
        return Optional.of(
                new ProviderPlayerScore(
                        Optional.of(new ExternalScore(new BigDecimal(value), count))));
    }

    private static ProviderWork work(String id, Optional<ProviderPlayerScore> score) {
        return work(id, ProviderWorkType.MAIN_GAME, ORIGINAL_PAGE, score, release(id + "0"));
    }

    private static ProviderWork work(
            String id,
            ProviderWorkType type,
            String attributionUrl,
            Optional<ProviderPlayerScore> score,
            ProviderRelease... releases) {
        return new ProviderWork(
                id,
                "Game " + id,
                type,
                RECORD_UPDATED,
                Optional.empty(),
                List.of(),
                Optional.ofNullable(attributionUrl),
                List.of(releases),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                score);
    }

    private static ProviderRelease release(String id) {
        return new ProviderRelease(
                id,
                new ProviderPlatform("6", "PC (Microsoft Windows)", "win"),
                Optional.of(new ProviderRegion("8", "worldwide")),
                new ReleaseDate.Day(LocalDate.parse("2026-05-01")),
                ProviderReleaseSignal.NONE,
                ReleaseStage.UNKNOWN);
    }

    /** The stored player score of a provider Game, timestamps as instants. */
    private Map<String, Object> score(String providerGameId) {
        Map<String, Object> row =
                new HashMap<>(
                        jdbc.queryForMap(
                                "SELECT s.source_name, s.score_kind, s.score, s.score_count,"
                                        + " s.source_url, s.provider_record_updated_at,"
                                        + " s.observed_at, s.changed_at"
                                        + " FROM catalogue.game_external_score s"
                                        + " JOIN catalogue.game_external_reference r"
                                        + " ON r.game_id = s.game_id"
                                        + " WHERE r.provider = 'IGDB' AND r.provider_id = ?",
                                providerGameId));
        row.replaceAll(
                (column, value) ->
                        value instanceof java.sql.Timestamp timestamp
                                ? timestamp.toInstant()
                                : value);
        return row;
    }

    private String gameId(String providerId) {
        return jdbc.queryForObject(
                "SELECT game_id::text FROM catalogue.game_external_reference"
                        + " WHERE provider='IGDB' AND provider_id=?",
                String.class,
                providerId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM catalogue." + table, Integer.class);
    }

    private String version() {
        return jdbc.queryForObject(
                "SELECT catalogue_version FROM catalogue.catalogue_publication", String.class);
    }

    private static final class FixtureProvider implements CatalogueProviderPort {
        final Map<String, ProviderWork> works = new HashMap<>();
        ProviderRequestException failure;

        void put(ProviderWork work) {
            works.put(work.providerId(), work);
        }

        @Override
        public String providerName() {
            return "IGDB";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public ReleasePage releaseGames(LocalDate from, LocalDate to, long afterGameId, int limit) {
            List<String> page =
                    works.keySet().stream()
                            .filter(id -> Long.parseLong(id) > afterGameId)
                            .sorted(java.util.Comparator.comparingLong(Long::parseLong))
                            .toList();
            return new ReleasePage(
                    page,
                    page.size(),
                    page.isEmpty() ? afterGameId : Long.parseLong(page.getLast()),
                    true,
                    ProviderCallStatistics.none());
        }

        @Override
        public ProviderWorkBatch fetchWorks(List<String> ids) {
            if (failure != null) {
                throw failure;
            }
            return new ProviderWorkBatch(
                    new ArrayList<>(ids.stream().map(works::get).filter(Objects::nonNull).toList()),
                    ProviderCallStatistics.none());
        }

        @Override
        public LogoBatch logos(List<String> ids) {
            return new LogoBatch(Map.of(), ProviderCallStatistics.none());
        }
    }
}
