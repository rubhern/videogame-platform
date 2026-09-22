package com.videogameplatform.catalogue.application.releases;

/** Public application contract for UC-001. */
public interface BrowseReleasesUseCase {

    BrowseReleasesResult browse(Query query);

    record Query(
            View view,
            int weeks,
            java.util.List<String> platformIds,
            java.util.List<String> regionIds,
            int pageNumber,
            int pageSize) {
        public Query {
            if (view == null) {
                throw new IllegalArgumentException("Release view is required");
            }
            platformIds = platformIds == null ? java.util.List.of() : java.util.List.copyOf(platformIds);
            regionIds = regionIds == null ? java.util.List.of() : java.util.List.copyOf(regionIds);
            if (weeks != 1 && weeks != 2 && weeks != 4) {
                throw new IllegalArgumentException("Release window must be 1, 2, or 4 weeks");
            }
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be one-based");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("Page size must be between 1 and 100");
            }
        }
    }

    enum View {
        RECENT,
        UPCOMING
    }
}
