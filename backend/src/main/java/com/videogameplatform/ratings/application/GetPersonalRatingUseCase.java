package com.videogameplatform.ratings.application;

public interface GetPersonalRatingUseCase {
    PersonalRating get(String userId, String gameId);
}
