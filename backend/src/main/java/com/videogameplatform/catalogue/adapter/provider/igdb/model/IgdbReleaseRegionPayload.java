package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB release-date region transport shape.
 *
 * <p>{@code id} is the stable provider entity identity the product maps through a typed external
 * reference. {@code region} is the mutable descriptive name only. A missing release region is not
 * guessed: it resolves to the product 'unknown' sentinel, which has no provider reference.
 */
public record IgdbReleaseRegionPayload(Long id, String region) {}
