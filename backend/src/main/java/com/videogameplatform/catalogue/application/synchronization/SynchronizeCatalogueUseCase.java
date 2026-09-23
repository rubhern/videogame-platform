package com.videogameplatform.catalogue.application.synchronization;

import java.util.Optional;

public interface SynchronizeCatalogueUseCase {
    CatalogueSynchronizationReport synchronize(CatalogueSynchronizationRequest request);

    Optional<CatalogueSynchronizationReport> lastRun();
}
