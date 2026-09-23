package com.videogameplatform.catalogue.application.details.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import java.util.List;
import java.util.Optional;

public interface GameListingReadPort {
    Optional<Listing> findListing(String gameId);

    record Listing(
            String gameId,
            String slug,
            String title,
            String normalizedTitle,
            List<String> normalizedAliases,
            CatalogueCoverReference cover) {}
}
