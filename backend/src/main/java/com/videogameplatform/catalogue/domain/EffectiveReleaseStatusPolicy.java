package com.videogameplatform.catalogue.domain;

import java.time.LocalDate;

/**
 * Derives the release status presented to clients from persisted provider evidence and the trusted
 * {@code Europe/Madrid} evaluation date (REL-011).
 *
 * <p>Persistence stores only evidence that cannot be derived from the release date and the current
 * evaluation date: the provider signal projected as {@link ReleaseStatus#ANNOUNCED} (no negative
 * signal), {@link ReleaseStatus#DELAYED} and {@link ReleaseStatus#CANCELLED}, plus an explicit
 * {@link ReleaseStatus#RELEASED} evidence reserved for a release that occurred without a temporal
 * threshold (an unknown date). For a known date, whether it has already occurred is a
 * time-dependent product decision derived here per request ({@code SCHEDULED} vs {@code RELEASED})
 * from the date alone, so a persisted {@code released} never overrides an unmet known date. This
 * keeps temporal behaviour independent of the last catalogue synchronization.
 */
public final class EffectiveReleaseStatusPolicy {

    private EffectiveReleaseStatusPolicy() {}

    public static ReleaseStatus effectiveStatus(
            ReleaseStatus persisted, ReleaseDate date, LocalDate evaluationDate) {
        return switch (persisted) {
            case CANCELLED -> ReleaseStatus.CANCELLED;
            case DELAYED -> ReleaseStatus.DELAYED;
            case ANNOUNCED, SCHEDULED, RELEASED, UNKNOWN ->
                    fromDate(persisted, date, evaluationDate);
        };
    }

    private static ReleaseStatus fromDate(
            ReleaseStatus persisted, ReleaseDate date, LocalDate evaluationDate) {
        if (date.precision() == ReleaseDate.Precision.UNKNOWN) {
            // No temporal threshold: only explicit released evidence proves an occurrence.
            return persisted == ReleaseStatus.RELEASED
                    ? ReleaseStatus.RELEASED
                    : ReleaseStatus.ANNOUNCED;
        }
        return date.hasOccurredBy(evaluationDate)
                ? ReleaseStatus.RELEASED
                : ReleaseStatus.SCHEDULED;
    }
}
