package com.videogameplatform.catalogue.application;

/** Public application contract for UC-001. */
public interface BrowseReleasesUseCase {

    ReleasePage browse(Query query);

    record Query(View view, String platformId, String regionId, int pageNumber, int pageSize) {}

    enum View {
        RECENT,
        UPCOMING
    }
}
