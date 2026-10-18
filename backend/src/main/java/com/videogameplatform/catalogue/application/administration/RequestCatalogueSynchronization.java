package com.videogameplatform.catalogue.application.administration;

import java.time.LocalDate;

/**
 * An operator's request for one synchronization of an inclusive release-date interval.
 *
 * <p>The answer is known before any provider work: the run is accepted with its identity, or
 * nothing starts. An accepted run continues outside the caller and reports its progress and result
 * through the retained run evidence, like every other synchronization.
 */
public interface RequestCatalogueSynchronization {
    /**
     * The widest interval one request may name: any calendar half-year, which also contains the
     * widest scheduled policy. Longer backfill is a separate, resumable workflow (#246).
     */
    int MAX_INTERVAL_DAYS = 184;

    /** The earliest supported day, matching the administration coverage calendar. */
    LocalDate EARLIEST = LocalDate.of(1950, 1, 1);

    /** The latest supported day, matching the administration coverage calendar. */
    LocalDate LATEST = LocalDate.of(2099, 12, 31);

    SynchronizationRequestOutcome request(LocalDate from, LocalDate to);
}
