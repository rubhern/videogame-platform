package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB release-date transport shape.
 *
 * <p>{@code date} is a Unix timestamp; {@code y}, {@code m} and {@code d} are the calendar fields
 * IGDB authored. Both are kept because they disagree at day boundaries and the calendar fields are
 * the timezone-free evidence.
 */
public record IgdbReleaseDatePayload(
        Long id,
        Long game,
        Long date,
        Integer y,
        Integer m,
        Integer d,
        IgdbNamedValuePayload dateFormat,
        IgdbNamedValuePayload releaseRegion,
        IgdbNamedValuePayload status,
        IgdbPlatformPayload platform) {}
