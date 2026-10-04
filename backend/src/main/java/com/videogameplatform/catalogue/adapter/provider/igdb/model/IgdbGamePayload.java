package com.videogameplatform.catalogue.adapter.provider.igdb.model;

import java.util.List;

/** IGDB game transport shape. */
public record IgdbGamePayload(
        Long id,
        String name,
        String url,
        String summary,
        Long createdAt,
        Long updatedAt,
        java.math.BigDecimal hypes,
        Long firstReleaseDate,
        Long versionParent,
        IgdbCoverPayload cover,
        List<IgdbImagePayload> artworks,
        List<IgdbImagePayload> screenshots,
        IgdbNamedValuePayload gameType,
        IgdbNamedValuePayload gameStatus,
        List<IgdbReleaseDatePayload> releaseDates,
        List<IgdbInvolvedCompanyPayload> involvedCompanies,
        List<IgdbTermPayload> genres,
        List<IgdbTermPayload> gameModes) {}
