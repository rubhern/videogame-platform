package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB image metadata for an artwork, screenshot or logo; {@code game} is present only where the
 * image is read by game. The binary is never requested.
 */
public record IgdbImagePayload(
        Long id,
        Long game,
        String imageId,
        Integer width,
        Integer height,
        Boolean alphaChannel,
        Boolean animated,
        ImageType imageType) {
    public record ImageType(String name) {}
}
