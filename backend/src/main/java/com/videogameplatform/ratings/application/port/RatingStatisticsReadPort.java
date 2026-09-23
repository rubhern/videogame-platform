package com.videogameplatform.ratings.application.port;

import com.videogameplatform.ratings.application.RatingStatistics;

public interface RatingStatisticsReadPort {
    RatingStatistics read(String gameId);
}
