package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationCoverage;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationCoverage.LastScan;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationCoverage.Period;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationCoverageService;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL evidence for durable coverage: interval algebra, fencing and retention. */
class SynchronizationCoverageIntegrationTest {
    private static final Instant T1 = Instant.parse("2026-10-01T04:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T04:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T04:00:00Z");
    private static final int RETAINED_RUNS = 50;
    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;
    private SynchronizationCoverageService coverage;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("coverage");
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
        coverage = new SynchronizationCoverageService(store);
    }

    @Test
    void successCoversItsExactInclusiveIntervalAndLeavesEveryGapNeverCovered() {
        LastScan january = cover("1997-01-01", "1997-01-31", T1);
        LastScan march = cover("1997-03-01", "1997-03-31", T2);

        assertThat(periods("1996-12-01", "1997-04-30"))
                .containsExactly(
                        never("1996-12-01", "1996-12-31"),
                        covered("1997-01-01", "1997-01-31", january),
                        never("1997-02-01", "1997-02-28"),
                        covered("1997-03-01", "1997-03-31", march),
                        never("1997-04-01", "1997-04-30"));
        assertThat(january.scanStartedAt()).isEqualTo(T1);
        assertThat(january.scanCompletedAt()).isEqualTo(T1.plus(Duration.ofMinutes(30)));
    }

    @Test
    void aNewerSuccessInsideAnOlderOneSplitsItAndKeepsBothTimes() {
        LastScan year = cover("1997-01-01", "1997-12-31", T1);
        LastScan june = cover("1997-06-01", "1997-06-30", T2);

        assertThat(periods("1997-01-01", "1997-12-31"))
                .containsExactly(
                        covered("1997-01-01", "1997-05-31", year),
                        covered("1997-06-01", "1997-06-30", june),
                        covered("1997-07-01", "1997-12-31", year));
        assertThat(storedIntervals()).isEqualTo(3);
    }

    @Test
    void aNewerSuccessCrossingBothEndsTrimsTheNeighboursAndReplacesWhatItContains() {
        LastScan first = cover("1997-01-01", "1997-03-31", T1);
        cover("1997-04-10", "1997-04-20", T1.plusSeconds(3600));
        LastScan third = cover("1997-05-01", "1997-07-31", T2);
        LastScan bridge = cover("1997-02-15", "1997-06-15", T3);

        assertThat(periods("1997-01-01", "1997-08-31"))
                .containsExactly(
                        covered("1997-01-01", "1997-02-14", first),
                        covered("1997-02-15", "1997-06-15", bridge),
                        covered("1997-06-16", "1997-07-31", third),
                        never("1997-08-01", "1997-08-31"));
        assertThat(storedIntervals()).isEqualTo(3);
    }

    @Test
    void aSupersetAndARepeatedRefreshEachLeaveOneIntervalWithTheLatestScan() {
        cover("1997-01-01", "1997-01-31", T1);
        cover("1997-03-01", "1997-03-31", T1.plusSeconds(60));
        cover("1996-12-15", "1998-01-15", T2);
        LastScan repeated = cover("1996-12-15", "1998-01-15", T3);

        assertThat(periods("1996-12-15", "1998-01-15"))
                .containsExactly(covered("1996-12-15", "1998-01-15", repeated));
        assertThat(storedIntervals()).isEqualTo(1);
    }

    @Test
    void adjacentSuccessesStayDistinctWithoutAGapAndBoundariesAreInclusive() {
        LastScan december = cover("1997-12-01", "1997-12-31", T1);
        LastScan january = cover("1998-01-01", "1998-01-31", T2);
        LastScan day = cover("1998-01-31", "1998-01-31", T3);

        assertThat(periods("1997-12-31", "1998-02-01"))
                .containsExactly(
                        covered("1997-12-31", "1997-12-31", december),
                        covered("1998-01-01", "1998-01-30", january),
                        covered("1998-01-31", "1998-01-31", day),
                        never("1998-02-01", "1998-02-01"));
    }

    @Test
    void coverageSurvivesPruningOfBoundedRunHistory() {
        LastScan first = cover("1997-01-01", "1997-01-31", T1, 1);
        LastScan second = cover("1997-02-01", "1997-02-28", T2, 1);
        LastScan third = cover("1997-03-01", "1997-03-31", T3, 1);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.synchronization_run",
                                Integer.class))
                .isEqualTo(1);
        assertThat(periods("1997-01-01", "1997-03-31"))
                .containsExactly(
                        covered("1997-01-01", "1997-01-31", first),
                        covered("1997-02-01", "1997-02-28", second),
                        covered("1997-03-01", "1997-03-31", third));
    }

    @Test
    void anAbandonedRunCannotRecordCoverageOrItsCompletion() {
        Instant systemNow = Instant.now();
        var request = request("1997-12-01", "1997-12-31");
        UUID abandoned =
                store.beginRun(
                                "IGDB",
                                request,
                                systemNow.minus(Duration.ofHours(2)),
                                Duration.ofMinutes(30))
                        .orElseThrow();
        store.beginRun(
                        "IGDB",
                        request("1990-01-01", "1990-01-31"),
                        systemNow,
                        Duration.ofMinutes(30))
                .orElseThrow();

        assertThatThrownBy(
                        () ->
                                store.completeCoveredRun(
                                        succeeded(abandoned, request, systemNow), RETAINED_RUNS))
                .isInstanceOf(SynchronizationWriteException.class);
        assertThat(periods("1997-12-01", "1997-12-31"))
                .containsExactly(never("1997-12-01", "1997-12-31"));
    }

    @Test
    void aCompletelyReadRunWithFailedGamesCoversWithItsCountAndReplacesACleanScan() {
        cover("1997-01-01", "1997-12-31", T1);
        LastScan withFailures = cover("1997-06-01", "1997-06-30", T2, RETAINED_RUNS, 4);

        assertThat(periods("1997-06-01", "1997-06-30"))
                .containsExactly(covered("1997-06-01", "1997-06-30", withFailures));
        assertThat(withFailures.clean()).isFalse();
        assertThat(periods("1997-05-31", "1997-05-31").getFirst().lastScan().orElseThrow().clean())
                .isTrue();
    }

    @Test
    void aRunWithoutASucceededGameCannotCoverAndARejectedOneWritesNothing() {
        var request = request("1997-12-01", "1997-12-31");
        UUID run = store.beginRun("IGDB", request, T1, Duration.ofDays(3650)).orElseThrow();
        var failed =
                new CatalogueSynchronizationReport(
                        run,
                        request.from(),
                        request.to(),
                        T1,
                        T1.plusSeconds(60),
                        SynchronizationOutcome.FAILED,
                        "SYNCHRONIZATION_FAILED",
                        counters(3));

        assertThatThrownBy(() -> store.completeCoveredRun(failed, RETAINED_RUNS))
                .isInstanceOf(IllegalArgumentException.class);
        store.completeRun(failed, RETAINED_RUNS);
        assertThat(periods("1997-12-01", "1997-12-31"))
                .containsExactly(never("1997-12-01", "1997-12-31"));
    }

    @Test
    void theDatabaseRejectsOverlappingIntervalsOfOneProviderButNotOfAnother() {
        String insert =
                "INSERT INTO catalogue.synchronization_coverage(provider,covered_from,covered_to,"
                        + "run_id,scan_started_at,scan_completed_at,failed_games)"
                        + " VALUES (?,?::date,?::date,?,?,?,0)";
        java.sql.Timestamp at = java.sql.Timestamp.from(T1);
        jdbc.update(insert, "IGDB", "1997-01-01", "1997-01-31", UUID.randomUUID(), at, at);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        "IGDB",
                                        "1997-01-31",
                                        "1997-02-28",
                                        UUID.randomUUID(),
                                        at,
                                        at))
                .isInstanceOf(DataAccessException.class);
        jdbc.update(insert, "OTHER", "1997-01-15", "1997-02-28", UUID.randomUUID(), at, at);
        assertThat(periods("1997-01-01", "1997-02-28")).hasSize(2);
    }

    @Test
    void aMultiDecadeRangeIsAnsweredInBoundedOrderedContinuations() {
        // 1,200 disjoint single-day intervals, every other day from 1980, exceed one answer.
        jdbc.update(
                """
                INSERT INTO catalogue.synchronization_coverage(provider,covered_from,covered_to,
                    run_id,scan_started_at,scan_completed_at,failed_games)
                SELECT 'IGDB', day, day, gen_random_uuid(), ?, ?, 0
                FROM generate_series(DATE '1980-01-02', DATE '1980-01-02' + 2 * 1199, interval '2 days')
                    AS series(day)
                """,
                java.sql.Timestamp.from(T1),
                java.sql.Timestamp.from(T1));
        LocalDate from = LocalDate.parse("1970-01-01");
        LocalDate to = LocalDate.parse("2029-12-31");

        List<Period> all = new ArrayList<>();
        LocalDate next = from;
        int answers = 0;
        while (true) {
            SynchronizationCoverage answer = coverage.coverage(next, to);
            answers++;
            assertThat(answer.periods()).hasSizeLessThanOrEqualTo(1001);
            all.addAll(answer.periods());
            if (answer.continuesFrom().isEmpty()) {
                break;
            }
            next = answer.continuesFrom().orElseThrow();
        }

        assertThat(answers).isEqualTo(3);
        assertThat(all.getFirst()).isEqualTo(never("1970-01-01", "1980-01-01"));
        assertThat(all.getLast()).isEqualTo(never("1986-07-28", "2029-12-31"));
        assertThat(all.stream().filter(Period::covered).count()).isEqualTo(1200);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).from()).isEqualTo(all.get(i - 1).to().plusDays(1));
        }
    }

    private LastScan cover(String from, String to, Instant started) {
        return cover(from, to, started, RETAINED_RUNS);
    }

    private LastScan cover(String from, String to, Instant started, int retainedRuns) {
        return cover(from, to, started, retainedRuns, 0);
    }

    private LastScan cover(
            String from, String to, Instant started, int retainedRuns, long failedGames) {
        var request = request(from, to);
        UUID run = store.beginRun("IGDB", request, started, Duration.ofDays(3650)).orElseThrow();
        store.completeCoveredRun(
                new CatalogueSynchronizationReport(
                        run,
                        request.from(),
                        request.to(),
                        started,
                        started.plus(Duration.ofMinutes(30)),
                        failedGames == 0
                                ? SynchronizationOutcome.SUCCEEDED
                                : SynchronizationOutcome.PARTIAL,
                        failedGames == 0
                                ? "SYNCHRONIZATION_COMPLETED"
                                : "SYNCHRONIZATION_COMPLETED_WITH_FAILURES",
                        counters(failedGames)),
                retainedRuns);
        return new LastScan(run, started, started.plus(Duration.ofMinutes(30)), failedGames);
    }

    private static CatalogueSynchronizationReport succeeded(
            UUID run, CatalogueSynchronizationRequest request, Instant started) {
        return new CatalogueSynchronizationReport(
                run,
                request.from(),
                request.to(),
                started,
                started.plus(Duration.ofMinutes(30)),
                SynchronizationOutcome.SUCCEEDED,
                "SYNCHRONIZATION_COMPLETED",
                zero());
    }

    private static CatalogueSynchronizationReport.Counters zero() {
        return counters(0);
    }

    /** Only the failed Game count matters to coverage. */
    private static CatalogueSynchronizationReport.Counters counters(long failedGames) {
        return new CatalogueSynchronizationReport.Counters(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                failedGames,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private List<Period> periods(String from, String to) {
        var answer = coverage.coverage(LocalDate.parse(from), LocalDate.parse(to));
        assertThat(answer.continuesFrom()).isEmpty();
        return answer.periods();
    }

    private int storedIntervals() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM catalogue.synchronization_coverage", Integer.class);
    }

    private static CatalogueSynchronizationRequest request(String from, String to) {
        return new CatalogueSynchronizationRequest(LocalDate.parse(from), LocalDate.parse(to));
    }

    private static Period covered(String from, String to, LastScan success) {
        return new Period(LocalDate.parse(from), LocalDate.parse(to), Optional.of(success));
    }

    private static Period never(String from, String to) {
        return new Period(LocalDate.parse(from), LocalDate.parse(to), Optional.empty());
    }
}
