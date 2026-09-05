package com.videogameplatform.catalogue.application.releases;

/** Public application contract for UC-001. */
public interface BrowseReleasesUseCase {

    BrowseReleasesResult browse(Query query);

    record Query(View view, String platformId, String regionId, int pageNumber, int pageSize) {
        public Query {
            if (view == null) {
                throw new IllegalArgumentException("Release view is required");
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
