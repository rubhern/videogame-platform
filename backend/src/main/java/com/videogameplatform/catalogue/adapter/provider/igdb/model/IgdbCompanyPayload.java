package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB company transport shape. {@code id} is the stable provider identity the product maps through a
 * typed external reference; {@code name} is descriptive only.
 */
public record IgdbCompanyPayload(Long id, String name) {}
