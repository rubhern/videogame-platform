package com.videogameplatform.ratings.application;

public interface DeletePersonalRatingUseCase {
    RatingStatistics.Available delete(String userId, String gameId, String expectedVersion);
}
