package com.videogameplatform.catalogue.application.synchronization;

import java.util.Optional;

public interface SynchronizeCatalogueUseCase {
    /** Takes ownership and synchronizes the interval on the calling thread. */
    CatalogueSynchronizationReport synchronize(CatalogueSynchronizationRequest request);

    /**
     * Takes ownership only, so a caller can answer before provider work and execute the run
     * elsewhere; {@link #synchronize} is this followed by {@link OwnedRun#execute()}.
     */
    SynchronizationStart start(CatalogueSynchronizationRequest request);

    /** Explicit bounded repair of known typed Game references, using normal reconciliation. */
    CatalogueSynchronizationReport repairGames(java.util.List<String> providerIds, boolean dryRun);

    Optional<CatalogueSynchronizationReport> lastRun();
}
