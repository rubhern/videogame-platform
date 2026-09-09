package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.time.LocalDate;

/**
 * Derives the product release status from provider evidence and the application evaluation date.
 *
 * <p>The provider is trusted only for cancellation and delay. Whether a date has already happened
 * is a time-dependent product rule, so it uses the explicit `Europe/Madrid` evaluation date the
 * application supplies (REL-011) and follows the same period boundaries as rating eligibility: an
 * exact day counts on the day itself, a month, quarter or year only once the period has ended.
 */
public final class ReleaseStatusPolicy {

    private ReleaseStatusPolicy() {}

    public static ReleaseStatus derive(
            ProviderReleaseSignal signal, ReleaseDate date, LocalDate evaluationDate) {
        return switch (signal) {
            case CANCELLED -> ReleaseStatus.CANCELLED;
            case DELAYED -> ReleaseStatus.DELAYED;
            case NONE -> fromDate(date, evaluationDate);
        };
    }

    private static ReleaseStatus fromDate(ReleaseDate date, LocalDate evaluationDate) {
        return switch (date) {
            case ReleaseDate.Unknown unknown -> ReleaseStatus.ANNOUNCED;
            case ReleaseDate.Day day ->
                    day.date().isAfter(evaluationDate)
                            ? ReleaseStatus.SCHEDULED
                            : ReleaseStatus.RELEASED;
            case ReleaseDate other ->
                    other.periodEnd().isBefore(evaluationDate)
                            ? ReleaseStatus.RELEASED
                            : ReleaseStatus.SCHEDULED;
        };
    }
}
