package com.videogameplatform.catalogue.application.details.internal;

import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.details.GameListing;
import com.videogameplatform.catalogue.application.details.GameNotFoundException;
import com.videogameplatform.catalogue.application.details.GetGameListingUseCase;
import com.videogameplatform.catalogue.application.details.port.GameListingReadPort;

public final class GameListingService implements GetGameListingUseCase {
    private final GameListingReadPort read;
    private final CatalogueCoverPolicy covers;

    public GameListingService(GameListingReadPort read, CatalogueCoverPolicy covers) {
        this.read = read;
        this.covers = covers;
    }

    @Override
    public GameListing getListing(String gameId) {
        var item = read.findListing(gameId).orElseThrow(GameNotFoundException::new);
        return new GameListing(
                item.gameId(),
                item.slug(),
                item.title(),
                item.normalizedTitle(),
                item.normalizedAliases(),
                covers.resolve(item.cover()));
    }
}
