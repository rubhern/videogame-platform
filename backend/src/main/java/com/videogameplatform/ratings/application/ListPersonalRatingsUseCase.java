package com.videogameplatform.ratings.application;

import java.util.List;

public interface ListPersonalRatingsUseCase {
    PersonalRatingsPage list(String userId, Query query);

    /**
     * {@code gameIds}, when not empty, restricts the user's ratings to those Games before search,
     * count and paging (a list of games reading its own page's ratings, #574).
     */
    record Query(
            String search,
            String sort,
            String direction,
            int page,
            int pageSize,
            List<String> gameIds) {
        public Query {
            gameIds = gameIds == null ? List.of() : List.copyOf(gameIds);
        }

        public Query(String search, String sort, String direction, int page, int pageSize) {
            this(search, sort, direction, page, pageSize, List.of());
        }
    }
}
