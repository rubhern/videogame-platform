package com.videogameplatform.catalogue.application.details;

import java.util.Objects;

/** A game of the current publication whose public page may be offered to search engines. */
public record IndexableGame(String gameId, String slug) {
    public IndexableGame {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(slug, "slug");
    }
}
