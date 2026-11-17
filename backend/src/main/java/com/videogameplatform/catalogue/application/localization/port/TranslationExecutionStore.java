package com.videogameplatform.catalogue.application.localization.port;

import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Action;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Coverage;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Execution;
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase.Batch;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface TranslationExecutionStore {
    Coverage coverage();

    Execution execution();

    Execution control(Action action, @Nullable UUID observedExecution);

    /** Refresh the lease and acquire the next checkpoint, or acknowledge a pending stop. */
    Execution next(UUID execution);

    /** Persist the batch and its cursor together, only while the worker owns the execution. */
    boolean checkpoint(UUID execution, Batch batch, boolean pauseForFailures);

    void workerFailed(UUID execution);
}
