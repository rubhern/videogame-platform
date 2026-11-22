package com.videogameplatform.catalogue.application.wishlist.internal;

import com.videogameplatform.catalogue.application.wishlist.RecordRatedGamesUseCase;
import com.videogameplatform.catalogue.application.wishlist.port.RatedGameStore;
import java.util.Collection;
import java.util.UUID;

/** Framework-independent maintenance of the rated-games projection (ADR-0051). */
public final class RatedGameService implements RecordRatedGamesUseCase {

    private final RatedGameStore store;

    public RatedGameService(RatedGameStore store) {
        this.store = store;
    }

    @Override
    public void rated(String userId, String gameId) {
        store.rated(UUID.fromString(userId), UUID.fromString(gameId));
    }

    @Override
    public void unrated(String userId, String gameId) {
        store.unrated(UUID.fromString(userId), UUID.fromString(gameId));
    }

    @Override
    public void backfill(Collection<RatedGame> games) {
        if (games.size() > MAX_BATCH) {
            throw new IllegalArgumentException("At most " + MAX_BATCH + " rated games per batch");
        }
        if (games.isEmpty()) return;
        store.backfill(
                games.stream()
                        .map(
                                game ->
                                        new RatedGameStore.Pair(
                                                UUID.fromString(game.userId()),
                                                UUID.fromString(game.gameId())))
                        .distinct()
                        .toList());
    }
}
