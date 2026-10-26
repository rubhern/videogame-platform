package com.videogameplatform.catalogue.application.rankings;

/**
 * Public application contract for UC-018: one page of the ranking of released games by the player
 * ratings of exactly one source (TOPómetro, ADR-0034).
 */
public interface RankGamesUseCase {

    int MAX_PAGE_SIZE = 100;

    GameRanking rank(Query query);

    /**
     * The one population whose ratings rank the games; sources are never combined. Each source
     * ranks only games whose score rests on at least its minimum number of ratings (RNK-002).
     */
    enum Source {
        /**
         * The IGDB players' rating mean and count stored with each game (XSC-001–XSC-004), from
         * one hundred ratings on.
         */
        IGDB_PLAYERS(100),
        /** The Gameómetro community mean of one contribution per rating (AGG-004), from the first. */
        GAMEOMETRO_COMMUNITY(1);

        private final int minimumRatings;

        Source(int minimumRatings) {
            this.minimumRatings = minimumRatings;
        }

        public int minimumRatings() {
            return minimumRatings;
        }
    }

    record Query(Source source, int pageNumber, int pageSize) {
        public Query {
            if (source == null) {
                throw new IllegalArgumentException("A ranking source is required");
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
