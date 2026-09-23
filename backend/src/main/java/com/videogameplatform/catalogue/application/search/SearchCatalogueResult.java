package com.videogameplatform.catalogue.application.search;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.util.List;

/**
 * Provider-independent result of searching the current local catalogue publication.
 *
 * <p>Every identifier is a product identifier. Provider identifiers, provider payloads and
 * the raw visitor query never appear here.
 */
public record SearchCatalogueResult(
        String publicationVersion, List<Item> items, PageMetadata page) {

    /**
     * One matching game. {@code matchedAlias} is present only when an approved alias
     * justified the match, and is then the best-ranked matching alias for that game.
     */
    public record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            String matchedAlias,
            CatalogueCover primaryCover,
            List<ReleaseContext> releaseContext,
            ReleaseSummary releaseSummary) {}

    /** Concise, explicitly bounded release context; never the game's complete release set. */
    public record ReleaseContext(
            Taxonomy platform,
            Taxonomy region,
            CatalogueReleaseDate releaseDate,
            CatalogueReleaseStatus status,
            CatalogueFreshness freshnessStatus) {}

    /**
     * Compact aggregate over the game's complete stored release set: a bounded head of the
     * ordered distinct platforms, the exact distinct-platform count, and the earliest and
     * latest known release years, which are both {@code null} when no date is known.
     */
    public record ReleaseSummary(
            List<Taxonomy> platforms,
            int totalPlatforms,
            Integer earliestKnownYear,
            Integer latestKnownYear) {}

    public record Taxonomy(String id, String name) {}

    public record PageMetadata(int number, int size, long totalItems, long totalPages) {}
}
