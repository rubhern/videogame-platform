package com.videogameplatform.catalogue.application.localization.internal;

import com.videogameplatform.catalogue.application.administration.CatalogueTranslations;
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.localization.port.TranslationExecutionStore;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

public final class CatalogueTranslationAdministration implements CatalogueTranslations {
    private static final int BATCH_SIZE = 3;
    private static final Duration COVERAGE_TTL = Duration.ofMinutes(1);
    private final TranslationExecutionStore store;
    private final LocalizeCatalogueUseCase localization;
    private final Executor worker;
    private final Clock clock;
    private @Nullable Coverage cachedCoverage;

    public CatalogueTranslationAdministration(
            TranslationExecutionStore store,
            LocalizeCatalogueUseCase localization,
            Executor worker,
            Clock clock) {
        this.store = store;
        this.localization = localization;
        this.worker = worker;
        this.clock = clock;
    }

    @Override
    public synchronized Coverage coverage() {
        if (cachedCoverage == null
                || !clock.instant().isBefore(cachedCoverage.observedAt().plus(COVERAGE_TTL))) {
            cachedCoverage = store.coverage();
        }
        return cachedCoverage;
    }

    @Override
    public Execution execution() {
        return store.execution();
    }

    @Override
    public Execution control(Action action, @Nullable UUID observedExecution) {
        var accepted = store.control(action, observedExecution);
        if (action != Action.STOP) {
            UUID id = accepted.id();
            try {
                worker.execute(() -> run(id));
            } catch (RejectedExecutionException unavailable) {
                store.workerFailed(id);
                return store.execution();
            }
        }
        return accepted;
    }

    private void run(UUID execution) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var next = store.next(execution);
                if (next.state() != State.RUNNING || !execution.equals(next.id())) return;
                var batch = localization.scan(next.afterKind(), next.afterId(), BATCH_SIZE);
                if (Thread.currentThread().isInterrupted()) break;
                // A fully unsuccessful batch can indicate an unavailable helper. A later
                // resume keeps the cursor so adjacent invalid sources cannot block the scan.
                boolean pauseForFailures =
                        !batch.completed() && batch.report().failed() == BATCH_SIZE;
                if (!store.checkpoint(execution, batch, pauseForFailures)) return;
            }
        } catch (RuntimeException failure) {
            // A stable reason is persisted. Source texts and infrastructure details stay private.
        }
        store.workerFailed(execution);
    }
}
