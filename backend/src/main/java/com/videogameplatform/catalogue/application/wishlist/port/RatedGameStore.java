package com.videogameplatform.catalogue.application.wishlist.port;

import java.util.List;
import java.util.UUID;

/** The rated-games projection; it writes inside the caller's transaction. */
public interface RatedGameStore {

    void rated(UUID userId, UUID gameId);

    void unrated(UUID userId, UUID gameId);

    void backfill(List<Pair> pairs);

    record Pair(UUID userId, UUID gameId) {}
}
