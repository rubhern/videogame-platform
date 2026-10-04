package com.videogameplatform.catalogue.application.releases;

import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/** Public application contract for UC-010: the automatic featured releases of a calendar month. */
public interface BrowseFeaturedReleasesUseCase {

    /**
     * @throws FeaturedMonthOutOfRangeException when the month lies outside the current calendar
     *     year at the trusted evaluation date
     */
    FeaturedReleasesResult browse(Query query);

    /**
     * The month to feature. Empty means the current calendar month at the trusted evaluation date;
     * a visitor never supplies the evaluation date itself. Only a month of that date's calendar
     * year can be featured.
     */
    record Query(Optional<YearMonth> month) {
        public Query {
            month = Objects.requireNonNull(month, "month");
            month.ifPresent(
                    value -> {
                        if (value.getYear() < 1 || value.getYear() > 9999) {
                            throw new IllegalArgumentException(
                                    "A featured month must fall in years 1 through 9999");
                        }
                    });
        }
    }
}
