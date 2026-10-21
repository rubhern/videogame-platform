package com.videogameplatform.ratings.application;

/** UC-015: the caller supplies only the trusted principal's product identity. */
public interface GetPersonalRatingInsightsUseCase {
    PersonalRatingInsights get(String userId);
}
