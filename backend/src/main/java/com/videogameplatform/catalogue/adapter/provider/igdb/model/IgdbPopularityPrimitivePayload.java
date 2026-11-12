package com.videogameplatform.catalogue.adapter.provider.igdb.model;

import java.math.BigDecimal;

/** One IGDB popularity primitive: a game's value of one popularity type at one calculation. */
public record IgdbPopularityPrimitivePayload(
        Long id, Long gameId, Long popularityType, BigDecimal value, Long calculatedAt) {}
