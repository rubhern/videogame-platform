package com.videogameplatform.catalogue.application.synchronization;

import java.time.LocalDate;

/** Durable synchronization coverage of release-date intervals, independent of run retention. */
public interface SynchronizationCoverageQuery {
    /**
     * Answers the inclusive range with a bounded number of periods.
     *
     * @throws IllegalArgumentException when the range is missing or {@code from} is after {@code
     *     to}
     */
    SynchronizationCoverage coverage(LocalDate from, LocalDate to);
}
