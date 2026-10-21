package com.videogameplatform.ratings.application;

public interface PutPersonalRatingUseCase {
    RatingCommandResult create(String userId, String gameId, PersonalRatingCommand command);

    RatingCommandResult update(
            String userId, String gameId, PersonalRatingCommand command, String expectedVersion);
}
