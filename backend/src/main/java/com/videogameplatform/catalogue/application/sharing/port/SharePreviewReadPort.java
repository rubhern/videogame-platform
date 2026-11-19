package com.videogameplatform.catalogue.application.sharing.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import java.util.Optional;
import java.util.UUID;

/** Outbound single-row reads behind link previews; each is one statement on a unique key. */
public interface SharePreviewReadPort {

    /** The game in the current publication, empty when it is not published. */
    Optional<Game> findGame(UUID gameId);

    Optional<String> findPlatformName(UUID platformId);

    /** {@code summary} is the displayed text, translated when a local translation exists. */
    record Game(
            String gameId,
            String slug,
            String canonicalTitle,
            String summary,
            CatalogueCoverReference cover) {}
}
