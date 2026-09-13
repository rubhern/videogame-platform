package com.videogameplatform.catalogue.application.details;

/** Synchronous publication notification; consumers refresh before the catalogue transaction commits. */
public record GameListingChanged(String gameId) {}
