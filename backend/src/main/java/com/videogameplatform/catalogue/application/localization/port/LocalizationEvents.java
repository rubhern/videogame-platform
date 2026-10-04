package com.videogameplatform.catalogue.application.localization.port;

import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase.Batch;

public interface LocalizationEvents {
    enum Outcome {
        TRANSLATED,
        REUSED,
        SKIPPED,
        FAILED,
        BUSY,
        FALLBACK
    }

    void content(LocalizationStore.Kind kind, Outcome outcome);

    void backfill(Batch batch);
}
