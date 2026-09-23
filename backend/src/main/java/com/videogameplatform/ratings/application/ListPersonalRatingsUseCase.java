package com.videogameplatform.ratings.application;

public interface ListPersonalRatingsUseCase {
    PersonalRatingsPage list(String userId, Query query);

    record Query(String search, String sort, String direction, int page, int pageSize) {}
}
