package com.videogameplatform.catalogue.application.releases;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Public application contract for UC-010: the automatic featured releases of a calendar week or
 * month.
 */
public interface BrowseFeaturedReleasesUseCase {

    /**
     * @throws FeaturedPeriodOutOfRangeException when the period lies outside the navigation bounds
     *     at the trusted evaluation date
     */
    FeaturedReleasesResult browse(Query query);

    /** The calendar period a selection represents: an ISO week (Monday to Sunday) or a month. */
    enum Granularity {
        WEEK,
        MONTH
    }

    /**
     * The period to feature, named by its first day: a Monday for a week, the first of the month
     * for a month. Empty means the current week or month at the trusted evaluation date; a
     * visitor never supplies the evaluation date itself.
     */
    record Query(Granularity granularity, Optional<LocalDate> periodStart) {
        public Query {
            Objects.requireNonNull(granularity, "granularity");
            Objects.requireNonNull(periodStart, "periodStart");
            periodStart.ifPresent(
                    start -> {
                        if (start.getYear() < 1 || start.getYear() > 9999) {
                            throw new IllegalArgumentException(
                                    "A featured period must start in years 1 through 9999");
                        }
                        boolean aligned =
                                Granularity.WEEK.equals(granularity)
                                        ? DayOfWeek.MONDAY.equals(start.getDayOfWeek())
                                        : start.getDayOfMonth() == 1;
                        if (!aligned) {
                            throw new IllegalArgumentException(
                                    "A featured period starts on a Monday or a first of month");
                        }
                    });
        }

        /** The current period of the granularity. */
        public static Query current(Granularity granularity) {
            return new Query(granularity, Optional.empty());
        }
    }
}
