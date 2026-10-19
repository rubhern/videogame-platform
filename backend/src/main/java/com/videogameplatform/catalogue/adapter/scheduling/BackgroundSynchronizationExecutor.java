package com.videogameplatform.catalogue.adapter.scheduling;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.observability.SynchronizationTrigger;
import com.videogameplatform.catalogue.application.synchronization.OwnedRun;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.BackgroundSynchronization;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Executes operator-requested runs on the composition root's executor and records them under the
 * {@code administration} trigger; the application still owns reconciliation and run ownership.
 *
 * <p>A run is submitted only after it holds the single database ownership, so runs never wait in a
 * queue and the executor's threads are bounded by that ownership, not by requests.
 */
public final class BackgroundSynchronizationExecutor
        implements BackgroundSynchronization, AutoCloseable {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(BackgroundSynchronizationExecutor.class);
    private static final SynchronizationTrigger TRIGGER = SynchronizationTrigger.ADMINISTRATION;
    private static final String TRIGGER_FIELD = "sync.trigger";
    private final ExecutorService executor;
    private final CatalogueSynchronizationMetrics metrics;

    public BackgroundSynchronizationExecutor(
            ExecutorService executor, CatalogueSynchronizationMetrics metrics) {
        this.executor = executor;
        this.metrics = metrics;
    }

    @Override
    public void submit(OwnedRun run) {
        executor.execute(() -> execute(run));
    }

    /** Rejects new runs and interrupts the one in flight; nothing waits for provider work. */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void execute(OwnedRun run) {
        long startedNanos = System.nanoTime();
        SynchronizationOutcome outcome = SynchronizationOutcome.FAILED;
        try {
            var report = run.execute();
            outcome = report.outcome();
            metrics.recordRun(report);
            LOGGER.atLevel(
                            switch (outcome) {
                                case FAILED -> Level.ERROR;
                                case PARTIAL -> Level.WARN;
                                default -> Level.INFO;
                            })
                    .addKeyValue(TRIGGER_FIELD, TRIGGER.type())
                    .addKeyValue("sync.outcome", outcome)
                    .addKeyValue("sync.code", report.outcomeCode())
                    .log(
                            "Catalogue synchronization requested result: outcome={} code={}",
                            outcome,
                            report.outcomeCode());
        } catch (RuntimeException failure) {
            // The run already recorded itself as failed; never log the message or payload.
            LOGGER.atError()
                    .addKeyValue(TRIGGER_FIELD, TRIGGER.type())
                    .addKeyValue("sync.code", "SYNCHRONIZATION_TRIGGER_FAILED")
                    .addKeyValue("sync.failure.type", failure.getClass().getSimpleName())
                    .log(
                            "Catalogue synchronization requested failure: code={} type={}",
                            "SYNCHRONIZATION_TRIGGER_FAILED",
                            failure.getClass().getSimpleName());
        } finally {
            metrics.recordTrigger(TRIGGER, outcome, startedNanos);
        }
    }
}
