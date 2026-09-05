package com.videogameplatform.catalogue.application.search;

/** Public application contract for UC-002. */
public interface SearchCatalogueUseCase {

    SearchCatalogueResult search(Query query);

    /**
     * The raw visitor query is carried untouched to the single normalization step so no
     * caller can smuggle a second, divergent normalization rule into the use case.
     */
    record Query(String text, int pageNumber, int pageSize) {
        public Query {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be one-based");
            }
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("Page size must be between 1 and 100");
            }
        }
    }
}
