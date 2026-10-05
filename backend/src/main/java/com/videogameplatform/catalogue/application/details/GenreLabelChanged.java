package com.videogameplatform.catalogue.application.details;

/** A localized product label changed; downstream projections retain the same term identity. */
public record GenreLabelChanged(GameDetailsResult.Term genre) {}
