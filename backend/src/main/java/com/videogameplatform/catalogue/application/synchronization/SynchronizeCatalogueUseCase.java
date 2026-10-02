package com.videogameplatform.catalogue.application.synchronization;

import java.util.Optional;

public interface SynchronizeCatalogueUseCase {
    CatalogueSynchronizationReport synchronize(CatalogueSynchronizationRequest request);

    /** Explicit bounded repair of known typed Game references, using normal reconciliation. */
    CatalogueSynchronizationReport repairGames(java.util.List<String> providerIds, boolean dryRun);

    Optional<CatalogueSynchronizationReport> lastRun();
}
