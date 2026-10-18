package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus.FinishedRun;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus.Result;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatusQuery;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationStatusService;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL evidence for the bounded run-status read over retained run history. */
class SynchronizationStatusIntegrationTest {
    private static final Instant T0 = Instant.parse("2026-10-01T04:00:00Z");
    private static final Duration ABANDON_AFTER = Duration.ofMinutes(30);
    private static final int RETAINED_RUNS = 50;
    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("sync_status");
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
    }

    @Test
    void aProviderThatNeverRanHasNoCurrentRunAndNoHistory() {
        SynchronizationStatus status = status(T0);

        assertThat(status.currentRun()).isEmpty();
        assertThat(status.recentRuns()).isEmpty();
    }

    @Test
    void reportsTheRunInFlightAndOnlyTheTwentyNewestFinishedRunsNewestFirst() {
        SynchronizationOutcome[] outcomes = {
            SynchronizationOutcome.SUCCEEDED,
            SynchronizationOutcome.PARTIAL,
            SynchronizationOutcome.FAILED
        };
        for (int run = 0; run < 25; run++) {
            finish(T0.plus(Duration.ofHours(run)), outcomes[run % 3], run);
        }
        Instant inFlight = T0.plus(Duration.ofHours(30));
        UUID running =
                store.beginRun("IGDB", request("2026-09-01", "2026-11-30"), inFlight, ABANDON_AFTER)
                        .orElseThrow();
        // Another provider's history never leaks into this one.
        jdbc.update(
                """
                INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,
                    started_at,heartbeat_at,run_status)
                VALUES (gen_random_uuid(),'OTHER','2026-01-01','2026-01-31',now(),now(),'running')
                """);

        SynchronizationStatus status = status(inFlight.plus(Duration.ofMinutes(5)));

        var current = status.currentRun().orElseThrow();
        assertThat(current.runId()).isEqualTo(running);
        assertThat(current.from()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(current.startedAt()).isEqualTo(inFlight);
        assertThat(current.lastHeartbeatAt()).isEqualTo(inFlight);
        assertThat(current.stalled()).isFalse();
        assertThat(status.recentRuns()).hasSize(SynchronizationStatusQuery.RECENT_RUNS);
        assertThat(status.recentRuns().getFirst().startedAt())
                .isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(status.recentRuns().getLast().startedAt())
                .isEqualTo(T0.plus(Duration.ofHours(5)));
        for (int i = 1; i < status.recentRuns().size(); i++) {
            assertThat(status.recentRuns().get(i).startedAt())
                    .isBefore(status.recentRuns().get(i - 1).startedAt());
        }
        FinishedRun newest = status.recentRuns().getFirst();
        assertThat(newest.result()).isEqualTo(Result.SUCCEEDED);
        assertThat(newest.outcomeCode()).isEqualTo("SYNCHRONIZATION_COMPLETED");
        assertThat(newest.counters().orElseThrow().failedGames()).isEqualTo(24);
        assertThat(newest.counters().orElseThrow().providerRequests()).isEqualTo(10);
        assertThat(status.recentRuns().get(1).result()).isEqualTo(Result.FAILED);
        assertThat(status.recentRuns().get(2).result()).isEqualTo(Result.PARTIAL);
    }

    @Test
    void anAbandonedRunHasNoInventedCountersAndAStaleRunInFlightIsStalled() {
        Instant now = T0.plus(Duration.ofDays(1));
        UUID abandoned =
                store.beginRun(
                                "IGDB",
                                request("1997-01-01", "1997-12-31"),
                                now.minus(Duration.ofHours(2)),
                                ABANDON_AFTER)
                        .orElseThrow();
        UUID stale =
                store.beginRun(
                                "IGDB",
                                request("1998-01-01", "1998-12-31"),
                                now.minus(Duration.ofHours(1)),
                                ABANDON_AFTER)
                        .orElseThrow();

        SynchronizationStatus status = status(now);

        assertThat(status.currentRun().orElseThrow().runId()).isEqualTo(stale);
        assertThat(status.currentRun().orElseThrow().stalled()).isTrue();
        FinishedRun closed = status.recentRuns().getFirst();
        assertThat(closed.runId()).isEqualTo(abandoned);
        assertThat(closed.result()).isEqualTo(Result.ABANDONED);
        assertThat(closed.outcomeCode()).isEqualTo("SYNCHRONIZATION_ABANDONED");
        assertThat(closed.counters()).isEmpty();
    }

    @Test
    void readsReportsRecordedBeforeLaterCountersExisted() {
        finish(T0, SynchronizationOutcome.PARTIAL, 2);
        jdbc.update(
                """
                UPDATE catalogue.synchronization_run
                SET report = report #- '{counters,deletedReleases}' #- '{counters,redundantReleases}'
                """);

        FinishedRun legacy = status(T0.plus(Duration.ofHours(1))).recentRuns().getFirst();

        assertThat(legacy.result()).isEqualTo(Result.PARTIAL);
        assertThat(legacy.counters().orElseThrow().deletedReleases()).isZero();
        assertThat(legacy.counters().orElseThrow().failedGames()).isEqualTo(2);
    }

    private SynchronizationStatus status(Instant now) {
        return new SynchronizationStatusService(
                        store,
                        Clock.fixed(now, ZoneOffset.UTC),
                        new SynchronizationPolicy(500, 25, RETAINED_RUNS, ABANDON_AFTER))
                .status();
    }

    private void finish(Instant started, SynchronizationOutcome outcome, long failedGames) {
        var request = request("2026-01-01", "2026-01-31");
        UUID run = store.beginRun("IGDB", request, started, Duration.ofDays(3650)).orElseThrow();
        var report =
                new CatalogueSynchronizationReport(
                        run,
                        request.from(),
                        request.to(),
                        started,
                        started.plus(Duration.ofMinutes(10)),
                        outcome,
                        switch (outcome) {
                            case SUCCEEDED -> "SYNCHRONIZATION_COMPLETED";
                            case PARTIAL -> "SYNCHRONIZATION_INTERRUPTED";
                            default -> "SYNCHRONIZATION_FAILED";
                        },
                        new CatalogueSynchronizationReport.Counters(
                                0,
                                1,
                                2,
                                3,
                                4,
                                5,
                                6,
                                7,
                                failedGames,
                                10,
                                1,
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
                                0));
        store.completeRun(report, RETAINED_RUNS);
    }

    private static CatalogueSynchronizationRequest request(String from, String to) {
        return new CatalogueSynchronizationRequest(LocalDate.parse(from), LocalDate.parse(to));
    }
}
