package com.videogameplatform.catalogue.application.releases.port;

import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Outbound query port for one month's release counts from the current catalogue publication. */
public interface ReleaseCalendarReadPort {

    /** Empty when the catalogue has never been published. */
    Optional<Result> countPublishedReleases(Criteria criteria);

    /**
     * The inclusive days to count, from the evaluation date or the month's first day to the month's
     * last day, classified exactly as {@link ReleaseBrowseReadPort.Criteria} classifies a range
     * without approximate dates; only exact days count.
     */
    record Criteria(
            LocalDate from,
            LocalDate to,
            LocalDate evaluatedOn,
            List<String> platformIds,
            List<String> regionIds) {

        public Criteria {
            if (from == null || to == null || evaluatedOn == null || to.isBefore(from)) {
                throw new IllegalArgumentException("A month and an evaluation date are required");
            }
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
            regionIds = regionIds == null ? List.of() : List.copyOf(regionIds);
        }
    }

    /**
     * The contextual facets of every counted release and the days with exact-day releases in
     * ascending order; an unknown selected filter value is absent from the facets and leaves the
     * counts empty.
     */
    record Result(List<Taxonomy> platforms, List<Taxonomy> regions, List<DayCount> days) {

        public Result {
            platforms = List.copyOf(platforms);
            regions = List.copyOf(regions);
            days = List.copyOf(days);
        }
    }

    record DayCount(LocalDate date, long gameCount, long totalHype) {}
}
