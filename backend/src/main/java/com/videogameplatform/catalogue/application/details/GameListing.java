package com.videogameplatform.catalogue.application.details;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.util.List;

/** Provider-independent current catalogue context for bounded downstream read models. */
public record GameListing(
        String gameId,
        String slug,
        String canonicalTitle,
        String normalizedTitle,
        List<String> normalizedAliases,
        CatalogueCover cover) {
    public GameListing {
        normalizedAliases = List.copyOf(normalizedAliases);
    }
}
