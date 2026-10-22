package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus.Result;
import com.videogameplatform.catalogue.application.administration.AbandonmentOutcome;
import com.videogameplatform.catalogue.application.administration.StopRequestOutcome;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.RunControl;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException.Reason;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL evidence for an operator's stop request and abandonment of one run: they act on
 * the named run of this provider only, are decided by single conditional statements, and race
 * safely with the worker's own fenced writes.
 */
class SynchronizationRunControlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Duration ABANDON_AFTER = Duration.ofMinutes(30);
    private static final Instant STALE_BEFORE = NOW.minus(ABANDON_AFTER);
    private static final CatalogueSynchronizationRequest WINDOW =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"));
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("sync_run_control");
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        dataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(database),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbc = new JdbcTemplate(dataSource);
        // The production write policy: READ COMMITTED is what re-checks a contended heartbeat.
        var transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        store =
                new JdbcCatalogueSynchronizationStore(
                        new NamedParameterJdbcTemplate(jdbc), transaction, "IGDB", gameId -> {});
    }

    @Test
    void aStopIsRecordedOnceOnTheNamedRunAndReadByItsNextSafePoint() {
        UUID run = begin(NOW.minusSeconds(60));
        // The database clock writes heartbeats, so age it against the real time.
        Instant aged = Instant.now().minus(Duration.ofMinutes(10));
        ageHeartbeat(run, aged);

        assertThat(store.checkpoint(run)).isEqualTo(RunControl.CONTINUE);
        // A safe point is also a heartbeat, so it is proof of life for an abandonment.
        assertThat(heartbeat(run)).isAfter(aged);
        var first = store.requestStop(run, NOW);
        var repeated = store.requestStop(run, NOW.plusSeconds(30));

        assertThat(first).isEqualTo(new StopRequestOutcome.Requested(run, NOW));
        assertThat(repeated).isEqualTo(new StopRequestOutcome.Requested(run, NOW));
        assertThat(store.checkpoint(run)).isEqualTo(RunControl.STOP_REQUESTED);
        assertThat(status(run)).isEqualTo("running");
        var current = store.retainedRuns(20).getFirst();
        assertThat(current.result()).isEmpty();
        assertThat(current.stopRequestedAt()).contains(NOW);
    }

    @Test
    void aStopOfAFinishedUnknownOrOtherProvidersRunChangesNothing() {
        UUID finished = begin(NOW.minusSeconds(60));
        store.completeRun(report(finished, SynchronizationOutcome.SUCCEEDED), 50);
        UUID other = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,"
                        + "started_at,heartbeat_at,run_status) VALUES (?,'OTHER','2026-10-01',"
                        + "'2026-12-31',now(),now(),'running')",
                other);

        assertThat(store.requestStop(finished, NOW)).isEqualTo(new StopRequestOutcome.Finished());
        assertThat(store.requestStop(UUID.randomUUID(), NOW))
                .isEqualTo(new StopRequestOutcome.NotFound());
        assertThat(store.requestStop(other, NOW)).isEqualTo(new StopRequestOutcome.NotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.synchronization_run"
                                        + " WHERE stop_requested_at IS NOT NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    void aRunWithARecentHeartbeatIsNeverAbandonedAndAFinishedOrUnknownOneChangesNothing() {
        UUID healthy = begin(NOW.minus(Duration.ofHours(2)));
        Instant recent = NOW.minus(Duration.ofMinutes(29));
        ageHeartbeat(healthy, recent);

        assertThat(store.abandonRun(healthy, NOW, STALE_BEFORE))
                .isEqualTo(new AbandonmentOutcome.NotStale(recent));
        assertThat(status(healthy)).isEqualTo("running");

        store.completeRun(report(healthy, SynchronizationOutcome.PARTIAL), 50);
        assertThat(store.abandonRun(healthy, NOW, STALE_BEFORE))
                .isEqualTo(new AbandonmentOutcome.Finished());
        assertThat(status(healthy)).isEqualTo("partial");
        assertThat(store.abandonRun(UUID.randomUUID(), NOW, STALE_BEFORE))
                .isEqualTo(new AbandonmentOutcome.NotFound());
    }

    @Test
    void aStaleRunIsAbandonedFencedAndFreesOwnershipOnce() {
        Instant started = NOW.minus(Duration.ofHours(2));
        UUID run = begin(started);
        ageHeartbeat(run, NOW.minus(Duration.ofMinutes(31)));
        store.requestStop(run, NOW.minus(Duration.ofMinutes(40)));

        var abandoned = store.abandonRun(run, NOW, STALE_BEFORE);

        assertThat(abandoned)
                .isEqualTo(
                        new AbandonmentOutcome.Abandoned(
                                run,
                                WINDOW.from(),
                                WINDOW.to(),
                                started,
                                NOW,
                                AbandonmentOutcome.ABANDONED_BY_OPERATOR));
        assertThat(store.abandonRun(run, NOW, STALE_BEFORE))
                .isEqualTo(new AbandonmentOutcome.Finished());
        // The old worker can neither prove life, write a Game, nor finish or cover its run.
        for (Runnable write :
                new Runnable[] {
                    () -> store.checkpoint(run),
                    () -> store.heartbeat(run),
                    () -> store.completeRun(report(run, SynchronizationOutcome.SUCCEEDED), 50),
                    () ->
                            store.completeCoveredRun(
                                    report(run, SynchronizationOutcome.SUCCEEDED), 50)
                }) {
            assertThatThrownBy(write::run)
                    .isInstanceOfSatisfying(
                            SynchronizationWriteException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.OWNERSHIP_LOST));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.synchronization_coverage",
                                Integer.class))
                .isZero();
        var history = store.retainedRuns(20).getFirst();
        assertThat(history.result()).contains(Result.ABANDONED);
        assertThat(history.outcomeCode()).contains("SYNCHRONIZATION_ABANDONED_BY_OPERATOR");
        assertThat(history.counters()).isEmpty();
        assertThat(history.stopRequestedAt()).contains(NOW.minus(Duration.ofMinutes(40)));
        // Ownership is free at once, without waiting for a later run start's takeover.
        assertThat(store.beginRun("IGDB", WINDOW, NOW, ABANDON_AFTER)).isPresent();
    }

    @Test
    void anAbandonmentWaitsForTheWorkersFencedWriteAndThenSeesTheHeartbeatItRenewed()
            throws Exception {
        // The worker renews its heartbeat with the database clock, so judge it on the real time.
        Instant now = Instant.now();
        UUID run = begin(now.minus(Duration.ofHours(2)));
        ageHeartbeat(run, now.minus(Duration.ofHours(1)));
        try (Connection worker = dataSource.getConnection()) {
            worker.setAutoCommit(false);
            worker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            // The worker's fence, as every Game write takes it, held open mid-write.
            try (PreparedStatement fence =
                    worker.prepareStatement(
                            "SELECT run_id FROM catalogue.synchronization_run WHERE run_id=? AND"
                                    + " run_status='running' FOR UPDATE")) {
                fence.setObject(1, run);
                fence.executeQuery().close();
            }
            try (PreparedStatement renew =
                    worker.prepareStatement(
                            "UPDATE catalogue.synchronization_run SET"
                                    + " heartbeat_at=CURRENT_TIMESTAMP WHERE run_id=?")) {
                renew.setObject(1, run);
                renew.executeUpdate();
            }

            CompletableFuture<AbandonmentOutcome> abandonment =
                    CompletableFuture.supplyAsync(
                            () -> store.abandonRun(run, now, now.minus(ABANDON_AFTER)));
            awaitLockWait();
            assertThat(abandonment).isNotDone();
            worker.commit();

            assertThat(abandonment.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(AbandonmentOutcome.NotStale.class);
        }
        assertThat(status(run)).isEqualTo("running");
    }

    @Test
    void aWorkerThatResumesAfterTheAbandonmentCommittedIsRefused() throws Exception {
        UUID run = begin(NOW.minus(Duration.ofHours(2)));
        ageHeartbeat(run, NOW.minus(Duration.ofHours(1)));

        assertThat(store.abandonRun(run, NOW, STALE_BEFORE))
                .isInstanceOf(AbandonmentOutcome.Abandoned.class);
        try (Connection worker = dataSource.getConnection();
                PreparedStatement fence =
                        worker.prepareStatement(
                                "SELECT run_id FROM catalogue.synchronization_run WHERE run_id=?"
                                        + " AND run_status='running' FOR UPDATE")) {
            fence.setObject(1, run);
            assertThat(fence.executeQuery().next()).isFalse();
        }
    }

    private void awaitLockWait() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Integer waiting =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                                    + " AND wait_event_type='Lock'",
                            Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The abandonment never waited for the worker's row lock");
    }

    private UUID begin(Instant startedAt) {
        // A period longer than any test's age keeps the run start from abandoning it itself.
        return store.beginRun("IGDB", WINDOW, startedAt, Duration.ofDays(3650)).orElseThrow();
    }

    private void ageHeartbeat(UUID run, Instant heartbeat) {
        jdbc.update(
                "UPDATE catalogue.synchronization_run SET heartbeat_at=? WHERE run_id=?",
                Timestamp.from(heartbeat),
                run);
    }

    private Instant heartbeat(UUID run) {
        return jdbc.queryForObject(
                        "SELECT heartbeat_at FROM catalogue.synchronization_run WHERE run_id=?",
                        Timestamp.class,
                        run)
                .toInstant();
    }

    private String status(UUID run) {
        return jdbc.queryForObject(
                "SELECT run_status FROM catalogue.synchronization_run WHERE run_id=?",
                String.class,
                run);
    }

    private static CatalogueSynchronizationReport report(UUID run, SynchronizationOutcome outcome) {
        return new CatalogueSynchronizationReport(
                run,
                WINDOW.from(),
                WINDOW.to(),
                NOW.minus(Duration.ofHours(2)),
                NOW,
                outcome,
                "SYNCHRONIZATION_COMPLETED",
                new CatalogueSynchronizationReport.Counters(
                        0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0));
    }
}
