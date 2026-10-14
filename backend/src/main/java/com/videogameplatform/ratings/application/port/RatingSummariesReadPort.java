package com.videogameplatform.ratings.application.port;

import com.videogameplatform.ratings.application.RatingStatistics;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface RatingSummariesReadPort {
    Map<String, Optional<RatingStatistics.Summary>> read(Set<String> gameIds);
}
