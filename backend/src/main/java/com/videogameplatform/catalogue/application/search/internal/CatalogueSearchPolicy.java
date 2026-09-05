package com.videogameplatform.catalogue.application.search.internal;

/** Product limits for a bounded UC-002 search. */
public record CatalogueSearchPolicy(int releaseContextLimit) {

    /** Fixed product bound matching OpenAPI CatalogueQuery; never runtime-tunable. */
    public static final int MAXIMUM_QUERY_CODE_POINTS = 100;

    public CatalogueSearchPolicy {
        if (releaseContextLimit < 1 || releaseContextLimit > 10) {
            throw new IllegalArgumentException(
                    "Release context per result must be between 1 and 10");
        }
    }
}
