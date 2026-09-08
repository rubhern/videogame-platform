package com.videogameplatform.catalogue.application.details;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import java.time.LocalDate;
import java.util.List;

/** One complete, bounded game context; deliberately contains no rating or user state. */
public record GameDetailsResult(
        String gameId,
        String slug,
        String canonicalTitle,
        List<String> aliases,
        Summary summary,
        CatalogueCover primaryCover,
        List<BrowseReleasesResult.Release> releases,
        LocalDate evaluatedOn) {
    public record Summary(
            String kind,
            String text,
            String language,
            BrowseReleasesResult.Provenance provenance) {}
}
