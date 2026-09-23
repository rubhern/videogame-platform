package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB platform transport shape.
 *
 * <p>{@code id} is the stable provider entity identity the product maps through a typed external
 * reference. {@code slug} and {@code name} are mutable descriptive metadata only: they may seed a
 * product code or display name, but they never establish or change product identity.
 */
public record IgdbPlatformPayload(Long id, String slug, String name) {}
