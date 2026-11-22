package com.videogameplatform.catalogue.application.wishlist;

import java.util.Collection;

/**
 * Keeps Catalogue's projection of the games each player has rated, the input of Mis juegos'
 * rated-without-state count and completion (UC-026, ADR-0051). Ratings owns every rating; it calls
 * this contract inside the transaction that creates or deletes one, so the projection commits or
 * rolls back with the rating. Catalogue never reads Ratings tables.
 */
public interface RecordRatedGamesUseCase {

    /** The most rated games one {@link #backfill} call accepts. */
    int MAX_BATCH = 500;

    void rated(String userId, String gameId);

    void unrated(String userId, String gameId);

    /** Records a bounded batch of existing ratings; already projected pairs are kept. */
    void backfill(Collection<RatedGame> games);

    record RatedGame(String userId, String gameId) {}
}
