package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.CoveredMonths;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.Rejected;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.State;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.OwnedRun;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationStart;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.internal.HistoricalBackfillService;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real checkpoint, exclusion, restart and stop semantics against Flyway-created PostgreSQL. */
class HistoricalBackfillIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private JdbcHistoricalBackfillStore store;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private SynchronizeCatalogueUseCase synchronization;
    private CatalogueSynchronizationStore runs;
    private HistoricalBackfillService service;
    private final Queue<Runnable> work = new ArrayDeque<>();

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("historical_backfill");
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
        store = new JdbcHistoricalBackfillStore(dataSource, "IGDB");
        synchronization = mock(SynchronizeCatalogueUseCase.class);
        runs = mock(CatalogueSynchronizationStore.class);
        service = new HistoricalBackfillService(store, runs, synchronization, work::add, CLOCK);
    }

    @Test
    void deterministicClippedCalendarMonthsIncludeLeapDayAndEmptyMonths() {
        var seen = new ArrayList<CatalogueSynchronizationRequest>();
        when(synchronization.start(any()))
                .thenAnswer(
                        call -> {
                            CatalogueSynchronizationRequest request = call.getArgument(0);
                            seen.add(request);
                            return started(
                                    request,
                                    SynchronizationOutcome.SUCCEEDED,
                                    "SYNCHRONIZATION_COMPLETED",
                                    0,
                                    0,
                                    0);
                        });
        service.start(date("2024-01-17"), date("2024-03-04"), CoveredMonths.RERUN);
        work.remove().run();
        assertThat(seen)
                .containsExactly(
                        window("2024-01-17", "2024-01-31"),
                        window("2024-02-01", "2024-02-29"),
                        window("2024-03-01", "2024-03-04"));
        assertThat(service.status().state()).isEqualTo(State.COMPLETED);
        assertThat(service.status().completedMonths()).isEqualTo(3);
        assertThat(service.status().progressPercent()).isEqualTo(100);
        assertThat(service.status().processedGames()).isZero();
        assertThatThrownBy(() -> service.resume(service.status().id()))
                .isInstanceOf(Rejected.class);
    }

    @Test
    void operationalFailurePausesAndNewServiceResumesOnlyPendingMonths() {
        var seen = new ArrayList<LocalDate>();
        when(synchronization.start(any()))
                .thenAnswer(
                        call -> {
                            CatalogueSynchronizationRequest request = call.getArgument(0);
                            seen.add(request.from());
                            boolean fail = request.from().getMonthValue() == 2 && seen.size() == 2;
                            return started(
                                    request,
                                    fail
                                            ? SynchronizationOutcome.PARTIAL
                                            : SynchronizationOutcome.SUCCEEDED,
                                    fail
                                            ? "SYNCHRONIZATION_INTERRUPTED"
                                            : "SYNCHRONIZATION_COMPLETED",
                                    2,
                                    fail ? 1 : 0,
                                    0);
                        });
        var accepted = service.start(date("2024-01-01"), date("2024-03-31"), CoveredMonths.RERUN);
        assertThatThrownBy(
                        () ->
                                service.start(
                                        date("2025-01-01"),
                                        date("2025-01-31"),
                                        CoveredMonths.RERUN))
                .isInstanceOf(Rejected.class);
        work.remove().run();
        assertThat(service.status().state()).isEqualTo(State.ATTENTION);
        assertThat(service.status().completedMonths()).isEqualTo(1);
        var restarted =
                new HistoricalBackfillService(
                        new JdbcHistoricalBackfillStore(dataSource, "IGDB"),
                        runs,
                        synchronization,
                        work::add,
                        CLOCK);
        assertThat(work).isEmpty(); // Construction and status never dispatch.
        assertThat(restarted.status().lastReason()).isEqualTo("SYNCHRONIZATION_INTERRUPTED");
        restarted.resume(accepted.id());
        work.remove().run();
        assertThat(restarted.status().state()).isEqualTo(State.COMPLETED);
        assertThat(seen)
                .containsExactly(
                        date("2024-01-01"),
                        date("2024-02-01"),
                        date("2024-02-01"),
                        date("2024-03-01"));
        assertThat(restarted.status().processedGames()).isEqualTo(6);
    }

    @Test
    void knownDataQualityFailuresCompleteAMonthWithoutClaimingErrorFreeAcquisition() {
        when(synchronization.start(any()))
                .thenAnswer(
                        call ->
                                started(
                                        call.getArgument(0),
                                        SynchronizationOutcome.PARTIAL,
                                        "SYNCHRONIZATION_COMPLETED_WITH_FAILURES",
                                        3,
                                        2,
                                        2));
        service.start(date("2020-11-01"), date("2020-11-30"), CoveredMonths.RERUN);
        work.remove().run();
        assertThat(service.status().state()).isEqualTo(State.COMPLETED);
        assertThat(service.status().dataQualityFailedGames()).isEqualTo(2);
        assertThat(service.status().processedGames()).isEqualTo(5);
    }

    @Test
    void skipRequiresCompleteCoverageAndExplicitIntentAndIsDurable() {
        coverage("2024-01-01", "2024-01-15");
        coverage("2024-01-16", "2024-02-29");
        when(synchronization.start(any()))
                .thenAnswer(
                        call ->
                                started(
                                        call.getArgument(0),
                                        SynchronizationOutcome.SUCCEEDED,
                                        "SYNCHRONIZATION_COMPLETED",
                                        1,
                                        0,
                                        0));
        assertThat(store.covered(date("2024-01-01"), date("2024-02-29"))).isTrue();
        assertThat(store.covered(date("2024-01-01"), date("2024-03-01"))).isFalse();
        service.start(date("2024-01-01"), date("2024-02-29"), CoveredMonths.SKIP);
        work.remove().run();
        assertThat(service.status().skippedMonths()).isEqualTo(2);
        verifyNoInteractions(synchronization);
        service.start(date("2024-01-01"), date("2024-01-31"), CoveredMonths.RERUN);
        work.remove().run();
        verify(synchronization).start(window("2024-01-01", "2024-01-31"));
    }

    @Test
    void lostProcessOwnershipReportsInterruptionWithoutStartingAnythingAndFencesOldExecution() {
        UUID id = UUID.randomUUID(), old;
        try (var lease = store.acquire().orElseThrow()) {
            store.create(id, date("2024-01-01"), date("2024-03-31"), CoveredMonths.RERUN, NOW);
            old = store.status(NOW).executionId();
            store.advance(id, old, date("2024-02-01"), true, null, NOW);
            assertThat(new JdbcHistoricalBackfillStore(dataSource, "IGDB").acquire()).isEmpty();
        }
        assertThat(service.status().state()).isEqualTo(State.INTERRUPTED);
        assertThat(service.status().completedMonths()).isEqualTo(1);
        assertThat(work).isEmpty();
        verifyNoInteractions(synchronization);
        try (var lease = store.acquire().orElseThrow()) {
            store.resume(id, NOW);
            store.state(id, old, State.ATTENTION, "STALE_WORKER", NOW);
            store.advance(id, old, date("2024-03-01"), true, null, NOW);
            assertThat(service.status().state()).isEqualTo(State.RUNNING);
            assertThat(service.status().nextFrom()).isEqualTo(date("2024-02-01"));
            String plan =
                    jdbc.queryForObject(
                            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT * FROM catalogue.historical_backfill",
                            String.class);
            assertThat(plan)
                    .contains("Actual Rows"); // Singleton read stays constant as catalogue grows.
        }
    }

    @Test
    void stopBeforeFirstMonthDoesNotStopAnUnrelatedOrdinaryRun() {
        var target = service.start(date("2024-01-01"), date("2024-03-31"), CoveredMonths.RERUN);
        assertThatThrownBy(() -> service.resume(UUID.randomUUID()))
                .isInstanceOf(Rejected.class)
                .hasMessage("NOT_FOUND");
        assertThat(service.stop(target.id()).state()).isEqualTo(State.STOPPING);
        work.remove().run();
        assertThat(service.status().state()).isEqualTo(State.STOPPED);
        assertThat(service.status().completedMonths()).isZero();
        verifyNoInteractions(runs, synchronization);
    }

    @Test
    void stopDuringMonthIsScopedToItsRunAndRetainsThePreviousCheckpoint() {
        UUID runId = UUID.randomUUID();
        when(synchronization.start(any()))
                .thenAnswer(
                        call -> {
                            CatalogueSynchronizationRequest request = call.getArgument(0);
                            OwnedRun run = mock(OwnedRun.class);
                            when(run.runId()).thenReturn(runId);
                            when(run.execute())
                                    .thenAnswer(
                                            ignored -> {
                                                var snapshot = service.status();
                                                service.stop(snapshot.id());
                                                store.progress(runId, counters(2, 0, 0), NOW);
                                                return report(
                                                        request,
                                                        runId,
                                                        SynchronizationOutcome.PARTIAL,
                                                        "SYNCHRONIZATION_STOPPED",
                                                        2,
                                                        0,
                                                        0);
                                            });
                            return new SynchronizationStart.Started(run);
                        });
        service.start(date("2024-01-01"), date("2024-03-31"), CoveredMonths.RERUN);
        work.remove().run();
        assertThat(service.status().state()).isEqualTo(State.STOPPED);
        assertThat(service.status().nextFrom()).isEqualTo(date("2024-01-01"));
        assertThat(service.status().currentGames()).isEqualTo(2);
        verify(runs).requestStop(runId, NOW);
        verifyNoMoreInteractions(runs);
    }

    @Test
    void scheduledReservationPreventsNextHistoricalMonthUntilRegularRunFinishes() throws Exception {
        var scheduled = store.acquireScheduledTurn().orElseThrow();
        when(synchronization.start(any()))
                .thenAnswer(
                        call ->
                                started(
                                        call.getArgument(0),
                                        SynchronizationOutcome.SUCCEEDED,
                                        "SYNCHRONIZATION_COMPLETED",
                                        1,
                                        0,
                                        0));
        service.start(date("2024-01-01"), date("2024-01-31"), CoveredMonths.RERUN);
        var finished = new CompletableFuture<Void>();
        Thread thread =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        work.remove().run();
                                        finished.complete(null);
                                    } catch (Throwable t) {
                                        finished.completeExceptionally(t);
                                    }
                                });
        try {
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (service.status().state() != State.WAITING && System.nanoTime() < until)
                Thread.sleep(10);
            assertThat(service.status().state()).isEqualTo(State.WAITING);
            verifyNoInteractions(synchronization);
            scheduled.close();
            finished.get(5, TimeUnit.SECONDS);
            assertThat(service.status().state()).isEqualTo(State.COMPLETED);
        } finally {
            scheduled.close();
            thread.interrupt();
            thread.join(5000);
        }
    }

    @Test
    void actualUc009CheckpointsEmptyMonthsAndDoesNotReacquireThemOnResume() {
        var ordinary =
                new JdbcCatalogueSynchronizationStore(
                        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                                jdbc),
                        new org.springframework.transaction.support.TransactionTemplate(
                                new org.springframework.jdbc.support.JdbcTransactionManager(
                                        dataSource)),
                        "IGDB",
                        id -> {});
        var provider =
                mock(
                        com.videogameplatform.catalogue.application.synchronization.port
                                .CatalogueProviderPort.class);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.providerName()).thenReturn("IGDB");
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(
                        new com.videogameplatform.catalogue.application.synchronization.port
                                .CatalogueProviderPort.ReleasePage(
                                List.of(),
                                0,
                                0,
                                true,
                                new com.videogameplatform.catalogue.application.synchronization.port
                                        .ProviderCallStatistics(1, 0, 0)));
        var uc009 =
                new com.videogameplatform.catalogue.application.synchronization.internal
                        .CatalogueSynchronizationService(
                        ordinary,
                        provider,
                        CLOCK,
                        new com.videogameplatform.catalogue.application.synchronization.internal
                                .SynchronizationPolicy(10, 25, 50, Duration.ofMinutes(30)),
                        new com.videogameplatform.catalogue.application.synchronization.internal
                                .CoverSelectionPolicy("/fallback.svg", "Product"),
                        com.videogameplatform.catalogue.application.synchronization.port
                                .SynchronizationProgress.NONE);
        var actual = new HistoricalBackfillService(store, ordinary, uc009, work::add, CLOCK);
        var target = actual.start(date("2024-01-01"), date("2024-02-29"), CoveredMonths.RERUN);
        work.remove().run();
        assertThat(actual.status().state()).isEqualTo(State.COMPLETED);
        assertThat(actual.status().completedMonths()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.synchronization_run WHERE run_status='succeeded'",
                                Long.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> actual.resume(target.id())).isInstanceOf(Rejected.class);
        verify(provider, times(2)).releaseGames(any(), any(), anyLong(), anyInt());
    }

    @Test
    void coverageSkipUsesTheExistingRangeIndexWithManyDisjointIntervals() {
        jdbc.update(
                """
            INSERT INTO catalogue.synchronization_coverage
            SELECT 'IGDB',DATE '1990-01-01'+2*n,DATE '1990-01-01'+2*n,
                   gen_random_uuid(),now(),now(),0 FROM generate_series(0,19999) AS n
            """);
        jdbc.execute("ANALYZE catalogue.synchronization_coverage");
        String plan =
                jdbc.queryForObject(
                        """
            EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)
            SELECT sum(LEAST(covered_to,DATE '2024-01-31')-GREATEST(covered_from,DATE '2024-01-01')+1)
            FROM catalogue.synchronization_coverage
            WHERE provider='IGDB' AND daterange(covered_from,covered_to,'[]') && daterange(DATE '2024-01-01',DATE '2024-01-31','[]')
            """,
                        String.class);
        assertThat(plan).contains("ex_synchronization_coverage_disjoint");
        assertThat(store.covered(date("2024-01-01"), date("2024-01-31"))).isFalse();
    }

    @Test
    void periodicSlotsAreDurableBoundedDeduplicatedAndFencedAgainstANewerWindow() {
        var near =
                com.videogameplatform.catalogue.application.synchronization.port
                        .ScheduledSynchronizationStore.Policy.NEAR_TERM;
        var upcoming =
                com.videogameplatform.catalogue.application.synchronization.port
                        .ScheduledSynchronizationStore.Policy.UPCOMING;
        store.defer(near, window("2026-10-01", "2026-10-31"), NOW);
        var first = store.next().orElseThrow();
        store.defer(near, first.window(), NOW.plusSeconds(1));
        assertThat(store.next().orElseThrow().requestId()).isEqualTo(first.requestId());
        store.defer(upcoming, window("2026-10-01", "2027-03-31"), NOW.plusSeconds(2));
        var restarted = new JdbcHistoricalBackfillStore(dataSource, "IGDB");
        assertThat(restarted.next()).contains(first);
        store.defer(near, window("2026-10-02", "2026-11-01"), NOW.plusSeconds(3));
        store.complete(first); // Cannot remove a newer due window while finishing an older run.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.deferred_synchronization_policy",
                                Long.class))
                .isEqualTo(2);
        assertThat(restarted.next().orElseThrow().window())
                .isEqualTo(window("2026-10-02", "2026-11-01"));
        store.defer(
                near,
                first.window(),
                NOW.minusSeconds(1)); // Late stale delivery never overwrites newer intent.
        assertThat(restarted.next().orElseThrow().window())
                .isEqualTo(window("2026-10-02", "2026-11-01"));
        restarted.cancel(near);
        assertThat(restarted.next().orElseThrow().policy()).isEqualTo(upcoming);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void bothDuePoliciesTakeTheBoundaryAndHistoryContinuesOnlyWhenNotStopped(boolean stopHistory)
            throws Exception {
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var runningMonth = new java.util.concurrent.atomic.AtomicBoolean();
        var registrar = new org.springframework.scheduling.config.ScheduledTaskRegistrar();
        new com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler(
                        synchronization,
                        new com.videogameplatform.catalogue.adapter.observability
                                .CatalogueSynchronizationMetrics(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                        CLOCK,
                        mock(org.springframework.scheduling.TaskScheduler.class),
                        new com.videogameplatform.catalogue.adapter.scheduling
                                .CatalogueSynchronizationScheduler.Policy("0 0 4 * * *", 28, 28),
                        new com.videogameplatform.catalogue.adapter.scheduling
                                .CatalogueSynchronizationScheduler.Policy("0 0 5 * * SUN", 0, 180),
                        store,
                        store)
                .configureTasks(registrar);
        Runnable drain = registrar.getFixedDelayTaskList().getFirst().getRunnable();
        when(synchronization.synchronize(any()))
                .thenAnswer(
                        call -> {
                            CatalogueSynchronizationRequest request = call.getArgument(0);
                            if (runningMonth.get())
                                return report(
                                        request,
                                        UUID.randomUUID(),
                                        SynchronizationOutcome.SKIPPED,
                                        "SYNCHRONIZATION_ALREADY_RUNNING",
                                        0,
                                        0,
                                        0);
                            calls.add(
                                    request.from().equals(LocalDate.now(CLOCK))
                                            ? "upcoming"
                                            : "near_term");
                            return report(
                                    request,
                                    UUID.randomUUID(),
                                    SynchronizationOutcome.SUCCEEDED,
                                    "SYNCHRONIZATION_COMPLETED",
                                    1,
                                    0,
                                    0);
                        });
        when(synchronization.start(any()))
                .thenAnswer(
                        call -> {
                            CatalogueSynchronizationRequest request = call.getArgument(0);
                            var run = mock(OwnedRun.class);
                            when(run.runId()).thenReturn(UUID.randomUUID());
                            when(run.execute())
                                    .thenAnswer(
                                            ignored -> {
                                                calls.add(
                                                        "history-"
                                                                + request.from().getMonthValue());
                                                if (request.from().getMonthValue() == 1) {
                                                    runningMonth.set(true);
                                                    registrar
                                                            .getTriggerTaskList()
                                                            .forEach(
                                                                    task ->
                                                                            task.getRunnable()
                                                                                    .run());
                                                    drain.run(); // Busy UC-009 preserves both
                                                    // slots rather than losing
                                                    // either.
                                                    runningMonth.set(false);
                                                }
                                                return report(
                                                        request,
                                                        run.runId(),
                                                        SynchronizationOutcome.SUCCEEDED,
                                                        "SYNCHRONIZATION_COMPLETED",
                                                        1,
                                                        0,
                                                        0);
                                            });
                            return new SynchronizationStart.Started(run);
                        });
        var target = service.start(date("2024-01-01"), date("2024-02-29"), CoveredMonths.RERUN);
        var finished = new CompletableFuture<Void>();
        Thread worker =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        work.remove().run();
                                        finished.complete(null);
                                    } catch (Throwable t) {
                                        finished.completeExceptionally(t);
                                    }
                                });
        try {
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (service.status().state() != State.WAITING && System.nanoTime() < until)
                Thread.sleep(10);
            assertThat(service.status().state()).isEqualTo(State.WAITING);
            assertThat(service.status().lastReason())
                    .isEqualTo("SCHEDULED_SYNCHRONIZATION_PENDING");
            if (stopHistory) service.stop(target.id());
            drain.run();
            finished.get(5, TimeUnit.SECONDS);
            assertThat(calls)
                    .containsExactlyElementsOf(
                            stopHistory
                                    ? List.of("history-1", "near_term", "upcoming")
                                    : List.of("history-1", "near_term", "upcoming", "history-2"));
            assertThat(service.status().state())
                    .isEqualTo(stopHistory ? State.STOPPED : State.COMPLETED);
            assertThat(store.next()).isEmpty();
            verifyNoInteractions(runs);
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test
    void aRestartDrainsPersistedRegularIntentWithoutResumingAStoppedHistoricalTarget() {
        UUID id = UUID.randomUUID();
        try (var lease = store.acquire().orElseThrow()) {
            store.create(id, date("2024-01-01"), date("2024-02-29"), CoveredMonths.RERUN, NOW);
            store.state(
                    id, store.status(NOW).executionId(), State.STOPPED, "HISTORICAL_STOPPED", NOW);
        }
        store.defer(
                com.videogameplatform.catalogue.application.synchronization.port
                        .ScheduledSynchronizationStore.Policy.NEAR_TERM,
                window("2026-10-01", "2026-10-31"),
                NOW);
        var restarted = new JdbcHistoricalBackfillStore(dataSource, "IGDB");
        var registrar = new org.springframework.scheduling.config.ScheduledTaskRegistrar();
        when(synchronization.synchronize(any()))
                .thenAnswer(
                        call ->
                                report(
                                        call.getArgument(0),
                                        UUID.randomUUID(),
                                        SynchronizationOutcome.SUCCEEDED,
                                        "SYNCHRONIZATION_COMPLETED",
                                        1,
                                        0,
                                        0));
        new com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler(
                        synchronization,
                        new com.videogameplatform.catalogue.adapter.observability
                                .CatalogueSynchronizationMetrics(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                        CLOCK,
                        mock(org.springframework.scheduling.TaskScheduler.class),
                        new com.videogameplatform.catalogue.adapter.scheduling
                                .CatalogueSynchronizationScheduler.Policy("0 0 4 * * *", 28, 28),
                        new com.videogameplatform.catalogue.adapter.scheduling
                                .CatalogueSynchronizationScheduler.Policy("0 0 5 * * SUN", 0, 180),
                        restarted,
                        restarted)
                .configureTasks(registrar);
        registrar.getFixedDelayTaskList().getFirst().getRunnable().run();
        assertThat(restarted.next()).isEmpty();
        assertThat(service.status().state()).isEqualTo(State.STOPPED);
        verify(synchronization, never()).start(any());
        assertThat(work).isEmpty();
    }

    @Test
    void invalidTargetsAreRejectedBeforeTakingOwnership() {
        assertThatThrownBy(
                        () ->
                                service.start(
                                        date("1949-12-31"), date("2024-01-01"), CoveredMonths.SKIP))
                .isInstanceOf(Rejected.class);
        assertThatThrownBy(
                        () ->
                                service.start(
                                        date("2024-02-01"), date("2024-01-31"), CoveredMonths.SKIP))
                .isInstanceOf(Rejected.class);
        assertThat(service.status().state()).isEqualTo(State.NOT_STARTED);
    }

    private void coverage(String from, String to) {
        jdbc.update(
                "INSERT INTO catalogue.synchronization_coverage VALUES('IGDB',?::date,?::date,?,?::timestamptz,?::timestamptz,0)",
                from,
                to,
                UUID.randomUUID(),
                NOW.toString(),
                NOW.toString());
    }

    private static SynchronizationStart started(
            CatalogueSynchronizationRequest request,
            SynchronizationOutcome outcome,
            String code,
            long games,
            long failed,
            long quality) {
        var id = UUID.randomUUID();
        var run = mock(OwnedRun.class);
        when(run.runId()).thenReturn(id);
        when(run.execute()).thenReturn(report(request, id, outcome, code, games, failed, quality));
        return new SynchronizationStart.Started(run);
    }

    private static CatalogueSynchronizationReport report(
            CatalogueSynchronizationRequest request,
            UUID id,
            SynchronizationOutcome outcome,
            String code,
            long games,
            long failed,
            long quality) {
        return new CatalogueSynchronizationReport(
                id,
                request.from(),
                request.to(),
                NOW,
                NOW.plusSeconds(1),
                outcome,
                code,
                counters(games, failed, quality));
    }

    private static CatalogueSynchronizationReport.Counters counters(
            long games, long failed, long quality) {
        return new CatalogueSynchronizationReport.Counters(
                0, games, 0, 0, 0, 0, 0, 0, failed, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, quality);
    }

    private static LocalDate date(String date) {
        return LocalDate.parse(date);
    }

    private static CatalogueSynchronizationRequest window(String from, String to) {
        return new CatalogueSynchronizationRequest(date(from), date(to));
    }
}
