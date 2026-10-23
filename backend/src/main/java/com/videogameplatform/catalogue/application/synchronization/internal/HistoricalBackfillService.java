package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.CoveredMonths;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.Rejected;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.Rejection;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.State;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.Status;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationAssessment;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationStart;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.HistoricalBackfillStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Sequential UC-009 orchestration. A month is replayed unless its checkpoint committed. */
public final class HistoricalBackfillService implements HistoricalSynchronization {
    private final HistoricalBackfillStore store;
    private final CatalogueSynchronizationStore runs;
    private final SynchronizeCatalogueUseCase synchronization;
    private final Executor executor;
    private final Clock clock;
    private final java.util.function.Consumer<CatalogueSynchronizationReport> completed;

    public HistoricalBackfillService(
            HistoricalBackfillStore store,
            CatalogueSynchronizationStore runs,
            SynchronizeCatalogueUseCase synchronization,
            Executor executor,
            Clock clock) {
        this(store, runs, synchronization, executor, clock, report -> {});
    }

    public HistoricalBackfillService(
            HistoricalBackfillStore store,
            CatalogueSynchronizationStore runs,
            SynchronizeCatalogueUseCase synchronization,
            Executor executor,
            Clock clock,
            java.util.function.Consumer<CatalogueSynchronizationReport> completed) {
        this.store = store;
        this.runs = runs;
        this.synchronization = synchronization;
        this.executor = executor;
        this.clock = clock;
        this.completed = completed;
    }

    @Override
    public Status status() {
        return store.status(clock.instant());
    }

    @Override
    public Status start(LocalDate from, LocalDate to, CoveredMonths intent) {
        if (from == null
                || to == null
                || intent == null
                || from.isBefore(LocalDate.of(1950, 1, 1))
                || to.isAfter(LocalDate.of(2099, 12, 31))
                || to.isBefore(from)) throw new Rejected(Rejection.RANGE_INVALID);
        var lease = store.acquire().orElseThrow(() -> new Rejected(Rejection.CONFLICT));
        try {
            var previous = status();
            if (previous.state() != State.NOT_STARTED && previous.state() != State.COMPLETED)
                throw new Rejected(Rejection.CONFLICT);
            UUID id = UUID.randomUUID();
            store.create(id, from, to, intent, clock.instant());
            dispatch(id, lease);
            return status();
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    @Override
    public Status resume(UUID id) {
        requireIdentity(id, status());
        var lease = store.acquire().orElseThrow(() -> new Rejected(Rejection.CONFLICT));
        try {
            var current = status();
            requireIdentity(id, current);
            if (current.state() == State.COMPLETED || current.state() == State.NOT_STARTED)
                throw new Rejected(Rejection.CONFLICT);
            // A surviving ordinary run must be stopped/abandoned through its own fenced controls.
            if (current.runId() != null
                    && runs.retainedRuns(1).stream()
                            .anyMatch(
                                    r -> r.runId().equals(current.runId()) && r.result().isEmpty()))
                throw new Rejected(Rejection.CONFLICT);
            store.resume(id, clock.instant());
            dispatch(id, lease);
            return status();
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    @Override
    public Status stop(UUID id) {
        var current = status();
        requireIdentity(id, current);
        if (current.state() == State.COMPLETED) throw new Rejected(Rejection.CONFLICT);
        store.stop(id, clock.instant());
        // attach() and stop() serialize on the target row; the worker rechecks before execute.
        current = status();
        requireIdentity(id, current);
        if (current.runId() != null) runs.requestStop(current.runId(), clock.instant());
        return status();
    }

    private static void requireIdentity(UUID id, Status current) {
        if (id == null || !id.equals(current.id())) throw new Rejected(Rejection.NOT_FOUND);
    }

    private void dispatch(UUID id, HistoricalBackfillStore.Lease lease) {
        UUID executionId = status().executionId();
        try {
            executor.execute(
                    new java.util.concurrent.FutureTask<Void>(
                            () -> {
                                execute(id, executionId, lease);
                                return null;
                            }) {
                        @Override
                        protected void done() {
                            lease.close();
                        }
                    });
        } catch (RuntimeException failure) {
            store.state(
                    id,
                    executionId,
                    State.ATTENTION,
                    "HISTORICAL_DISPATCH_FAILED",
                    clock.instant());
            throw failure;
        }
    }

    private void execute(UUID id, UUID executionId, HistoricalBackfillStore.Lease lease) {
        try (lease) {
            while (true) {
                lease.check();
                var current = status();
                if (!id.equals(current.id()) || !executionId.equals(current.executionId())) return;
                if (current.state() == State.STOPPING || current.state() == State.STOPPED) {
                    store.state(
                            id, executionId, State.STOPPED, "HISTORICAL_STOPPED", clock.instant());
                    return;
                }
                if (Thread.currentThread().isInterrupted()) {
                    store.state(
                            id,
                            executionId,
                            State.INTERRUPTED,
                            "HISTORICAL_INTERRUPTED",
                            clock.instant());
                    return;
                }
                if (current.nextFrom().isAfter(current.to())) {
                    store.state(id, executionId, State.COMPLETED, null, clock.instant());
                    return;
                }
                LocalDate from = current.nextFrom();
                LocalDate end = monthEnd(from, current.to());
                if (current.coveredMonths() == CoveredMonths.SKIP && store.covered(from, end)) {
                    store.advance(id, executionId, end.plusDays(1), true, null, clock.instant());
                    continue;
                }
                if (store.scheduledTurnPending()) {
                    store.state(
                            id,
                            executionId,
                            State.WAITING,
                            "SCHEDULED_SYNCHRONIZATION_PENDING",
                            clock.instant());
                    pause();
                    continue;
                }
                switch (synchronization.start(new CatalogueSynchronizationRequest(from, end))) {
                    case SynchronizationStart.Skipped skipped -> {
                        if (skipped.reason() == SynchronizationStart.Reason.DISABLED) {
                            store.state(
                                    id,
                                    executionId,
                                    State.ATTENTION,
                                    "SYNCHRONIZATION_DISABLED",
                                    clock.instant());
                            return;
                        }
                        store.state(
                                id,
                                executionId,
                                State.WAITING,
                                "SYNCHRONIZATION_ALREADY_RUNNING",
                                clock.instant());
                        pause();
                    }
                    case SynchronizationStart.Started started -> {
                        var run = started.run();
                        try {
                            lease.check();
                            store.attach(id, executionId, run.runId(), end, clock.instant());
                            if (status().state() == State.STOPPING)
                                runs.requestStop(run.runId(), clock.instant());
                        } catch (RuntimeException failure) {
                            run.cancel();
                            throw failure;
                        }
                        var report = run.execute();
                        completed.accept(report);
                        lease.check();
                        switch (SynchronizationAssessment.of(report)) {
                            case CLEAN, DATA_QUALITY ->
                                    store.advance(
                                            id,
                                            executionId,
                                            end.plusDays(1),
                                            false,
                                            report,
                                            clock.instant());
                            case STOPPED -> {
                                store.state(
                                        id,
                                        executionId,
                                        State.STOPPED,
                                        report.outcomeCode(),
                                        clock.instant());
                                return;
                            }
                            default -> {
                                store.state(
                                        id,
                                        executionId,
                                        State.ATTENTION,
                                        report.outcomeCode(),
                                        clock.instant());
                                return;
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            store.state(
                    id, executionId, State.INTERRUPTED, "HISTORICAL_INTERRUPTED", clock.instant());
        } catch (RuntimeException failure) {
            // The execution token fences this write if another instance already resumed.
            store.state(
                    id,
                    executionId,
                    State.ATTENTION,
                    "HISTORICAL_EXECUTION_FAILED",
                    clock.instant());
        }
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(1000);
    }

    public static LocalDate monthEnd(LocalDate from, LocalDate requestedTo) {
        var end = from.withDayOfMonth(from.lengthOfMonth());
        return end.isAfter(requestedTo) ? requestedTo : end;
    }
}
