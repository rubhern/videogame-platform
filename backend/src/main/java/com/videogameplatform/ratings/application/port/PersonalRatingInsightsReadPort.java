package com.videogameplatform.ratings.application.port;

import com.videogameplatform.ratings.application.PersonalRatingInsights;
import java.time.LocalDate;

public interface PersonalRatingInsightsReadPort {
    PersonalRatingInsights read(String userId, LocalDate evaluatedOn);
}
