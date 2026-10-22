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
     * featured evidence was unavailable or invalid. {@code detailsUnavailableGames} counts committed
     * Games that kept their last valid summary, credits, genres and game modes because the provider's
     * detail metadata was invalid. The player-score counters count committed Games by what happened
     * to their external player score: a usable score observed, the provider stating none (no score
     * or zero ratings), or the last valid score kept because the answer was invalid or could not be
     * attributed to its work. The player-score change counters say what those writes did to the
     * stored usable score: created where none was, updated to another value or count, removed by a
     * valid answer stating none, or re-checked unchanged. A Game that neither held nor now holds a
     * usable score changes none of them. {@code redundantReleases} counts returned release references
     * of committed Games that restated another reference's Release and were not stored (ADR-0017);
     * they are neither failures nor withheld updates. {@code dataQualityFailedGames} is the part of
     * {@code failedGames} whose failure was a known, isolated data-quality failure of the provider's
     * own record; a report written before it existed reads zero.
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
            long logoUnavailableGames,
            long detailsUnavailableGames,
            long playerScoreObservedGames,
            long playerScoreAbsentGames,
            long playerScoreUnavailableGames,
            long playerScoreCreatedGames,
            long playerScoreUpdatedGames,
            long playerScoreRemovedGames,
            long playerScoreUnchangedGames,
            long redundantReleases,
            long dataQualityFailedGames) {}
}
