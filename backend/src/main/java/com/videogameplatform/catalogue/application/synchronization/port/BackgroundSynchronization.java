package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.synchronization.OwnedRun;

/**
 * Executes an owned run outside the requesting thread, so the requester can answer before any
 * provider work. The executor owns its threads and the run's trigger telemetry, never its rules.
 */
public interface BackgroundSynchronization {
    /**
     * Accepts the run for exactly one {@link OwnedRun#execute()} on another thread.
     *
     * @throws java.util.concurrent.RejectedExecutionException when the run cannot be accepted, for
     *     example while the application stops; the run was then not touched
     */
    void submit(OwnedRun run);
}
