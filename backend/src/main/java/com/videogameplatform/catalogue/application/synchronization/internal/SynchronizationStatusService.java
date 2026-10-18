package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus.CurrentRun;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus.FinishedRun;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatusQuery;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.RetainedRun;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the run in flight and the most recent finished runs from retained run evidence.
 *
 * <p>PostgreSQL returns at most one run in flight plus {@link #RECENT_RUNS} finished runs, so the
 * answer is bounded whatever history retention allows. A run in flight is reported as stalled by
 * the same abandonment period that lets the next run start take its ownership over.
 */
public final class SynchronizationStatusService implements SynchronizationStatusQuery {
    private final CatalogueSynchronizationStore store;
    private final Clock clock;
    private final Duration abandonRunAfter;

    public SynchronizationStatusService(
            CatalogueSynchronizationStore store, Clock clock, SynchronizationPolicy policy) {
        this.store = store;
        this.clock = clock;
        this.abandonRunAfter = policy.abandonRunAfter();
    }

    @Override
    public SynchronizationStatus status() {
        Instant now = clock.instant();
        Optional<CurrentRun> current = Optional.empty();
        List<FinishedRun> finished = new ArrayList<>();
        for (RetainedRun run : store.retainedRuns(RECENT_RUNS)) {
            if (run.result().isEmpty()) {
                current =
                        Optional.of(
                                new CurrentRun(
                                        run.runId(),
                                        run.from(),
                                        run.to(),
                                        run.startedAt(),
                                        run.heartbeatAt(),
                                        run.heartbeatAt().isBefore(now.minus(abandonRunAfter))));
            } else if (finished.size() < RECENT_RUNS) {
                finished.add(
                        new FinishedRun(
                                run.runId(),
                                run.from(),
                                run.to(),
                                run.startedAt(),
                                run.completedAt().orElseThrow(),
                                run.result().orElseThrow(),
                                run.outcomeCode().orElseThrow(),
                                run.counters().map(SynchronizationStatusService::counters)));
            }
        }
        return new SynchronizationStatus(current, finished);
    }

    private static SynchronizationStatus.Counters counters(
            CatalogueSynchronizationReport.Counters reported) {
        return new SynchronizationStatus.Counters(
                reported.createdGames(),
                reported.updatedGames(),
                reported.unchangedGames(),
                reported.deferredGames(),
                reported.failedGames(),
                reported.createdReleases(),
                reported.updatedReleases(),
                reported.unchangedReleases(),
                reported.deletedReleases(),
                reported.providerRequests(),
                reported.providerRetries());
    }
}
