package com.videogameplatform.catalogue.application.platformgames;

import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import java.util.List;
import java.util.Objects;

/**
 * One page of a platform scope's games in the current local publication. Every identifier is a
 * product identifier; each game appears once, presented by one release of the scope.
 */
public record PlatformGames(
        String publicationVersion,
        List<Taxonomy> platforms,
        Filters availableFilters,
        List<Item> items,
        PageMetadata page) {

    public PlatformGames {
        platforms = List.copyOf(platforms);
        Objects.requireNonNull(availableFilters, "availableFilters");
        items = List.copyOf(items);
    }

    /**
     * The scope's contextual choices: genres of the games matching every other filter, and the
     * known years of the presented releases matching every filter but the year range, both null
     * when none is known.
     */
    public record Filters(
            List<GameDetailsResult.Term> genres, Integer earliestYear, Integer latestYear) {
        public Filters {
            genres = List.copyOf(genres);
            if ((earliestYear == null) != (latestYear == null)) {
                throw new IllegalArgumentException("Known years must be both present or absent");
            }
        }
    }

    /** One game; {@code matchedAlias} only when the search matched an alias and not the title. */
    public record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            String matchedAlias,
            CatalogueCover primaryCover,
            List<GameDetailsResult.Term> genres,
            PlatformRelease platformRelease) {
        public Item {
            genres = List.copyOf(genres);
            Objects.requireNonNull(platformRelease, "platformRelease");
        }
    }

    /** The release that presents the game on the scope, at its own precision and status. */
    public record PlatformRelease(
            Taxonomy platform,
            Taxonomy region,
            CatalogueReleaseDate releaseDate,
            CatalogueReleaseStatus status) {}

    public record Taxonomy(String id, String name) {}

    public record PageMetadata(int number, int size, long totalItems, long totalPages) {}
}
