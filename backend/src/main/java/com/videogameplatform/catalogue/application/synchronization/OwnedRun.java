package com.videogameplatform.catalogue.application.synchronization;

import java.time.Instant;
import java.util.UUID;

/**
 * A run that already holds the provider's synchronization ownership and has not done any provider
 * work yet. Exactly one of {@link #execute()} and {@link #cancel()} may be called, once; either
 * one releases the ownership by completing the run.
 */
public interface OwnedRun {
    UUID runId();

    CatalogueSynchronizationRequest request();

    Instant startedAt();

    /**
     * Synchronizes the interval with the ordinary reconciliation and completes the run.
     *
     * @throws IllegalStateException when the run was already executed or cancelled
     */
    CatalogueSynchronizationReport execute();

    /**
     * Completes the run as failed without any provider work, for a run that could not be handed
     * to its worker.
     *
     * @throws IllegalStateException when the run was already executed or cancelled
     */
    CatalogueSynchronizationReport cancel();
}
