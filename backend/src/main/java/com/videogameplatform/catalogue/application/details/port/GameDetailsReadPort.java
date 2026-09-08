package com.videogameplatform.catalogue.application.details.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import java.util.List;
import java.util.Optional;

/** Reads a complete game in one publication, never silently truncating release evidence. */
public interface GameDetailsReadPort {
    int MAX_RELEASES = 256;
    int MAX_ALIASES = 100;

    Optional<Game> find(String gameId);

    record Game(
            String gameId,
            String slug,
            String canonicalTitle,
            List<String> aliases,
            GameDetailsResult.Summary summary,
            CatalogueCoverReference cover,
            List<ReleaseBrowseReadPort.Item> releases) {}
}
