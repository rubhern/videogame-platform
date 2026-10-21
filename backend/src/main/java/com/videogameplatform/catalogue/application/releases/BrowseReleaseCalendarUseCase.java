package com.videogameplatform.catalogue.application.releases;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Public application contract for UC-016: a month's release calendar. */
public interface BrowseReleaseCalendarUseCase {

    /**
     * @throws ReleaseQueryValidationException with {@code MONTH_OUT_OF_RANGE} when the month lies
     *     outside the navigation bounds at the trusted evaluation date, or with {@code
     *     PLATFORM_NOT_SUPPORTED} or {@code REGION_NOT_SUPPORTED} for an unknown filter value
     */
    ReleaseCalendarResult browse(Query query);

    /**
     * The month to count, empty for the current month at the trusted evaluation date, which a
     * visitor never supplies. Platform and region filters combine as in {@link
     * BrowseReleasesUseCase}.
     */
    record Query(Optional<YearMonth> month, List<String> platformIds, List<String> regionIds) {
        public Query {
            Objects.requireNonNull(month, "month");
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
            regionIds = regionIds == null ? List.of() : List.copyOf(regionIds);
            month.ifPresent(
                    value -> {
                        if (value.getYear() < 1 || value.getYear() > 9999) {
                            throw new IllegalArgumentException(
                                    "A calendar month must fall in years 1 through 9999");
                        }
                    });
        }
    }
}
