package com.videogameplatform.catalogue.application.synchronization;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Operational history, independent of the catalogue revision. */
public record CatalogueSynchronizationReport(
        UUID runId,
        LocalDate from,
        LocalDate to,
        Instant startedAt,
        Instant completedAt,
        SynchronizationOutcome outcome,
        String outcomeCode,
        Counters counters) {
    public record Counters(
            long inspectedReleaseDates,
            long createdGames,
            long updatedGames,
            long unchangedGames,
            long createdReleases,
            long updatedReleases,
            long unchangedReleases,
            long deferredGames,
            long failedGames,
            long providerRequests,
            long providerRetries,
            long providerLatencyMillis) {}
}
