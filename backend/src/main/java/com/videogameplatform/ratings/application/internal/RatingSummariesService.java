package com.videogameplatform.ratings.application.internal;

import com.videogameplatform.ratings.application.GetRatingSummariesUseCase;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.port.RatingSummariesReadPort;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RatingSummariesService implements GetRatingSummariesUseCase {
    private final RatingSummariesReadPort read;

    public RatingSummariesService(RatingSummariesReadPort read) {
        this.read = read;
    }

    @Override
    public Map<String, Optional<RatingStatistics.Summary>> get(Set<String> gameIds) {
        if (gameIds.size() > MAX_GAMES)
            throw new IllegalArgumentException("Rating summary batch exceeds discovery page bound");
        return gameIds.isEmpty() ? Map.of() : Map.copyOf(read.read(Set.copyOf(gameIds)));
    }
}
