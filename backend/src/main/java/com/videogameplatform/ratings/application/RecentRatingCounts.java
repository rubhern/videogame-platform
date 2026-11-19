package com.videogameplatform.ratings.application;

import java.time.Instant;
import java.util.List;

public interface RecentRatingCounts {
    List<Count> ranking(Instant start, Instant end);

    record Count(String gameId, long count) {}
}
