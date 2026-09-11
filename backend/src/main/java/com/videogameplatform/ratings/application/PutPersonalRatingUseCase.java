package com.videogameplatform.ratings.application;

public interface PutPersonalRatingUseCase {
    RatingCommandResult create(String userId, String gameId, int value);

    RatingCommandResult update(String userId, String gameId, int value, String expectedVersion);
}
