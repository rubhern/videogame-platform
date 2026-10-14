package com.videogameplatform.ratings.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded public community summaries; empty Optional means unavailable, never zero ratings. */
public interface GetRatingSummariesUseCase {
    int MAX_GAMES = 100;

    Map<String, Optional<RatingStatistics.Summary>> get(Set<String> gameIds);
}
