package com.videogameplatform.catalogue.adapter.provider.igdb.model;

import java.util.List;

/** IGDB game transport shape. */
public record IgdbGamePayload(
        Long id,
        String name,
        String url,
        Long createdAt,
        Long updatedAt,
        IgdbCoverPayload cover,
        IgdbNamedValuePayload gameType,
        IgdbNamedValuePayload gameStatus,
        List<IgdbReleaseDatePayload> releaseDates) {}
