package com.videogameplatform.catalogue.application.sharing;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.util.Optional;

/**
 * Reads what a shared link's preview may name (ADR-0049). Each call is one indexed single-row
 * read of the current local catalogue: no provider call, no user state.
 */
public interface GetSharePreviewUseCase {

    /**
     * The game in the current publication, or empty when the identifier is not a product game
     * identifier or the game is not published.
     *
     * @throws com.videogameplatform.catalogue.application.CatalogueReadException on a retryable
     *     read failure
     */
    Optional<GamePreview> game(String gameId);

    /** A platform's catalogue name, or empty when the identifier names no platform. */
    Optional<String> platformName(String platformId);

    /**
     * {@code summary} is the text the game page shows, its local Spanish translation when one
     * exists, and null when the game has none. {@code cover} uses the share rendition: the same
     * approved cover the page shows, in a format every preview client decodes.
     */
    record GamePreview(
            String gameId,
            String slug,
            String canonicalTitle,
            String summary,
            CatalogueCover cover) {}
}
