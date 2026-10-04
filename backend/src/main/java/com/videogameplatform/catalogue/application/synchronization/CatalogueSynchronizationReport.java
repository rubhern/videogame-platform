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
    /**
     * The popularity counters count committed Games by what happened to their popularity signal:
     * observed and recorded, absent because the provider reports zero or no Hypes, or kept unchanged because
     * featured evidence was unavailable or invalid.
     */
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
            long providerLatencyMillis,
            long deletedReleases,
            long popularityObservedGames,
            long popularityClearedGames,
            long popularityUnavailableGames,
            long featuredImageObservedGames,
            long logoObservedGames,
            long logoUnavailableGames) {}
}
