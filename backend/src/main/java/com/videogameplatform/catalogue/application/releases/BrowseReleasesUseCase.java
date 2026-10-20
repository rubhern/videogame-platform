package com.videogameplatform.catalogue.application.releases;

import java.time.LocalDate;

/** Public application contract for UC-001. */
public interface BrowseReleasesUseCase {

    /** The longest inclusive range one request may explore, in days. */
    int MAX_RANGE_DAYS = 92;

    BrowseReleasesResult browse(Query query);

    /**
     * One bounded release page request over an inclusive date range. Both bounds are absent for
     * the current calendar week, which only the application's trusted clock knows, or both are
     * present. {@code includeApproximateDates} admits month, quarter and year periods that have
     * not ended in the part of the range from the evaluation date on; it changes nothing before
     * that date. The range itself is validated by the use case, which reports {@link
     * ReleaseQueryValidationException}.
     */
    record Query(
            LocalDate from,
            LocalDate to,
            Sort sort,
            boolean includeApproximateDates,
            java.util.List<String> platformIds,
            java.util.List<String> regionIds,
            int pageNumber,
            int pageSize) {
        public Query {
            if (sort == null) {
                throw new IllegalArgumentException("Release sort is required");
            }
            platformIds =
                    platformIds == null ? java.util.List.of() : java.util.List.copyOf(platformIds);
            regionIds = regionIds == null ? java.util.List.of() : java.util.List.copyOf(regionIds);
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be one-based");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("Page size must be between 1 and 100");
            }
        }
    }

    /**
     * The order of the games before pagination. Every order continues with the release-date order
     * and ends in the unique game id; a missing signal sorts after every present one.
     */
    enum Sort {
        RELEASE_DATE,
        HYPE,
        RATING,
        /** The locally stored external player score, currently IGDB players. */
        EXTERNAL_PLAYER_SCORE
    }
}
