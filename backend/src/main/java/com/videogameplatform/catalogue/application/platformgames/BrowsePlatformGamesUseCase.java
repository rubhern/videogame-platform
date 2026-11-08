package com.videogameplatform.catalogue.application.platformgames;

import java.util.List;

/**
 * Public application contract for UC-019 (Explorar catálogo): one page of the locally known games
 * of one platform or platform family, without a search query.
 */
public interface BrowsePlatformGamesUseCase {

    int MAX_PAGE_SIZE = 100;
    int MAX_PLATFORMS = 20;
    int MAX_GENRES = 30;

    PlatformGames browse(Query query);

    /** The order of the games; every order ends in the unique game. */
    enum Sort {
        RELEASE_DATE_DESC,
        RELEASE_DATE_ASC,
        TITLE_ASC,
        TITLE_DESC,
        COMMUNITY_SCORE,
        EXTERNAL_PLAYER_SCORE
    }

    /** Whether a game's presented release on the scope has effectively occurred, or not yet. */
    enum ReleaseState {
        RELEASED,
        UPCOMING
    }

    /**
     * The visitor's selection. The raw search text, when present, is carried untouched to the
     * single normalization step; a null release state, year or text means no such filter.
     */
    record Query(
            List<String> platformIds,
            String text,
            ReleaseState releaseState,
            Integer fromYear,
            Integer toYear,
            List<String> genreIds,
            Sort sort,
            int pageNumber,
            int pageSize) {
        public Query {
            platformIds = List.copyOf(platformIds);
            genreIds = List.copyOf(genreIds);
            if (sort == null) {
                throw new IllegalArgumentException("An order is required");
            }
            if (platformIds.size() > MAX_PLATFORMS || genreIds.size() > MAX_GENRES) {
                throw new IllegalArgumentException("Platform and genre selections are bounded");
            }
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be one-based");
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Page size must be between 1 and 100");
            }
        }
    }
}
