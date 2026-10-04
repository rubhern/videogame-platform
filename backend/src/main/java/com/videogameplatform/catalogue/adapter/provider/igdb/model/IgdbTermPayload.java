package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB genre or game-mode transport shape. {@code id} is the stable provider identity the product
 * maps through a typed external reference; {@code name} and {@code slug} are descriptive only.
 */
public record IgdbTermPayload(Long id, String name, String slug) {}
