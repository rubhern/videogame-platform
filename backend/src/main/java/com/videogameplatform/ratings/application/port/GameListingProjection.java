package com.videogameplatform.ratings.application.port;

/** Rebuilds public game context through Catalogue's application contract. */
public interface GameListingProjection {
    void refresh(String gameId);
}
