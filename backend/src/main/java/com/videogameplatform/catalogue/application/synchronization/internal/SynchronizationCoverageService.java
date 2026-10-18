package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverage;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverage.Period;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverageQuery;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CoveredInterval;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Derives consecutive covered and never-covered periods from the stored disjoint intervals.
 *
 * <p>PostgreSQL selects and orders at most {@link #MAX_COVERED_INTERVALS} stored intervals, so one
 * answer holds at most twice that many periods plus one, whatever the range length. A longer answer
 * continues from the first unanswered day.
 */
public final class SynchronizationCoverageService implements SynchronizationCoverageQuery {
    static final int MAX_COVERED_INTERVALS = 500;

    private final CatalogueSynchronizationStore store;

    public SynchronizationCoverageService(CatalogueSynchronizationStore store) {
        this.store = store;
    }

    @Override
    public SynchronizationCoverage coverage(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || to.equals(LocalDate.MAX)) {
            throw new IllegalArgumentException("Invalid synchronization coverage range");
        }
        List<CoveredInterval> stored = store.coveredIntervals(from, to, MAX_COVERED_INTERVALS + 1);
        boolean bounded = stored.size() > MAX_COVERED_INTERVALS;
        List<Period> periods = new ArrayList<>();
        LocalDate next = from;
        for (CoveredInterval interval :
                bounded ? stored.subList(0, MAX_COVERED_INTERVALS) : stored) {
            LocalDate start = interval.from().isBefore(from) ? from : interval.from();
            LocalDate end = interval.to().isAfter(to) ? to : interval.to();
            if (start.isAfter(next)) {
                periods.add(new Period(next, start.minusDays(1), Optional.empty()));
            }
            periods.add(new Period(start, end, Optional.of(interval.lastScan())));
            next = end.plusDays(1);
        }
        if (bounded) {
            return new SynchronizationCoverage(from, to, periods, Optional.of(next));
        }
        if (!next.isAfter(to)) {
            periods.add(new Period(next, to, Optional.empty()));
        }
        return new SynchronizationCoverage(from, to, periods, Optional.empty());
    }
}
