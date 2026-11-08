package com.videogameplatform.catalogue.application.releases;

import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-independent result of UC-020. {@code window} is the six-calendar-month horizon, first
 * and last day inclusive. {@code items} is the ranking, highest Hypes first: each game once, with
 * its consecutive position and the qualifying release that presents it on the scope. Hypes are
 * attention, never quality, and their value never leaves the store.
 */
public record UpcomingHighlightsResult(
        List<BrowseReleasesResult.Taxonomy> platforms,
        LocalDate evaluatedOn,
        BrowseReleasesResult.Window window,
        FeaturedReleasesResult.Selection selection,
        List<Item> items) {

    public UpcomingHighlightsResult {
        platforms = List.copyOf(platforms);
        Objects.requireNonNull(evaluatedOn, "evaluatedOn");
        Objects.requireNonNull(window, "window");
        items = List.copyOf(items);
        if (items.isEmpty() == selection instanceof FeaturedReleasesResult.Selection.Ranked) {
            throw new IllegalArgumentException("Only a ranked selection holds upcoming highlights");
        }
    }

    /** One ranked game; the first uses the hero image policy and the others the card policy. */
    public record Item(
            int position,
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCover primaryCover,
            FeaturedReleasesResult.FeaturedImage image,
            List<GameDetailsResult.Term> genres,
            PlatformRelease platformRelease) {
        public Item {
            Objects.requireNonNull(image, "image");
            genres = List.copyOf(genres);
            Objects.requireNonNull(platformRelease, "platformRelease");
        }
    }

    /** The qualifying release that presents the game on the scope, at its own precision. */
    public record PlatformRelease(
            BrowseReleasesResult.Taxonomy platform,
            BrowseReleasesResult.Taxonomy region,
            CatalogueReleaseDate releaseDate,
            CatalogueReleaseStatus status) {
        public PlatformRelease {
            Objects.requireNonNull(platform, "platform");
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(releaseDate, "releaseDate");
            Objects.requireNonNull(status, "status");
        }
    }
}
