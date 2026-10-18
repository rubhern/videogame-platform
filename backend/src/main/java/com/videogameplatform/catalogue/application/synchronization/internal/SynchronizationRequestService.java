package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.administration.RequestCatalogueSynchronization;
import com.videogameplatform.catalogue.application.administration.SynchronizationRequestOutcome;
import com.videogameplatform.catalogue.application.administration.SynchronizationRequestOutcome.Bound;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.OwnedRun;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationStart;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.port.BackgroundSynchronization;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.RejectedExecutionException;

/**
 * Validates an operator's interval, takes the single synchronization ownership on the caller's
 * thread and hands the owned run to the background executor.
 *
 * <p>Ownership is the same fenced database run every trigger takes, so a request never runs beside
 * a scheduled or management run, and an accepted run is the ordinary synchronization.
 */
public final class SynchronizationRequestService implements RequestCatalogueSynchronization {
    private final SynchronizeCatalogueUseCase synchronization;
    private final BackgroundSynchronization background;

    public SynchronizationRequestService(
            SynchronizeCatalogueUseCase synchronization, BackgroundSynchronization background) {
        this.synchronization = synchronization;
        this.background = background;
    }

    @Override
    public SynchronizationRequestOutcome request(LocalDate from, LocalDate to) {
        if (from == null || from.isBefore(EARLIEST) || from.isAfter(LATEST)) {
            return new SynchronizationRequestOutcome.RangeInvalid(Bound.FROM);
        }
        if (to == null
                || to.isBefore(from)
                || to.isAfter(LATEST)
                || ChronoUnit.DAYS.between(from, to) >= MAX_INTERVAL_DAYS) {
            return new SynchronizationRequestOutcome.RangeInvalid(Bound.TO);
        }
        return switch (synchronization.start(new CatalogueSynchronizationRequest(from, to))) {
            case SynchronizationStart.Skipped skipped ->
                    switch (skipped.reason()) {
                        case DISABLED -> new SynchronizationRequestOutcome.Disabled();
                        case ALREADY_RUNNING -> new SynchronizationRequestOutcome.AlreadyRunning();
                    };
            case SynchronizationStart.Started started -> submit(started.run());
        };
    }

    private SynchronizationRequestOutcome submit(OwnedRun run) {
        try {
            background.submit(run);
        } catch (RejectedExecutionException failure) {
            // Release the ownership at once instead of leaving it to the abandonment period.
            run.cancel();
            throw failure;
        }
        return new SynchronizationRequestOutcome.Accepted(
                run.runId(), run.request().from(), run.request().to(), run.startedAt());
    }
}
