package com.videogameplatform.catalogue.adapter.observability;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.port.HistoricalBackfillStore;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Bounded progress snapshots on the existing observation seam; observations never fail a run. */
public final class HistoricalSynchronizationProgress implements SynchronizationProgress {
    private final SynchronizationProgress delegate;
    private final HistoricalBackfillStore store;
    private final Clock clock;

    public HistoricalSynchronizationProgress(
            SynchronizationProgress delegate, HistoricalBackfillStore store, Clock clock) {
        this.delegate = delegate;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void skipped(CatalogueSynchronizationReport report) {
        delegate.skipped(report);
    }

    @Override
    public Run started(
            UUID id, CatalogueSynchronizationRequest request, Instant started, int pageSize) {
        var delegateRun = delegate.started(id, request, started, pageSize);
        return new Run() {
            private Instant next = Instant.MIN;

            private void snapshot(Counters counters, boolean force) {
                var now = clock.instant();
                if (!force && now.isBefore(next)) return;
                next = now.plusSeconds(5);
                try {
                    store.progress(id, counters, now);
                } catch (RuntimeException failure) {
                    // The final report/checkpoint is authoritative. Do not log payloads.
                    org.slf4j.LoggerFactory.getLogger(HistoricalSynchronizationProgress.class)
                            .warn("Historical synchronization progress snapshot unavailable");
                }
            }

            @Override
            public void pageRequested(int page, Counters counters) {
                delegateRun.pageRequested(page, counters);
                snapshot(counters, false);
            }

            @Override
            public void pageFetched(int page, int games, Counters counters) {
                delegateRun.pageFetched(page, games, counters);
                snapshot(counters, false);
            }

            @Override
            public void pageLookupUnavailable(
                    int page, int games, Failure failure, Counters counters) {
                delegateRun.pageLookupUnavailable(page, games, failure, counters);
                snapshot(counters, false);
            }

            @Override
            public void gameSucceeded(
                    int page, int position, GameResult result, Counters counters) {
                delegateRun.gameSucceeded(page, position, result, counters);
                snapshot(counters, false);
            }

            @Override
            public void gameFailed(
                    int page,
                    int position,
                    Failure failure,
                    Optional<SynchronizedGameIdentity> game,
                    Counters counters) {
                delegateRun.gameFailed(page, position, failure, game, counters);
                snapshot(counters, false);
            }

            @Override
            public void pageCompleted(int page, Counters counters) {
                delegateRun.pageCompleted(page, counters);
                snapshot(counters, false);
            }

            @Override
            public void runFailed(Failure failure, Counters counters) {
                delegateRun.runFailed(failure, counters);
                snapshot(counters, false);
            }

            @Override
            public void finished(CatalogueSynchronizationReport report) {
                delegateRun.finished(report);
                snapshot(report.counters(), true);
            }
        };
    }
}
