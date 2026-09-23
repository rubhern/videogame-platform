package com.videogameplatform.ratings.application;

/** Personal and aggregate state observed in the command transaction. */
public record RatingCommandResult(
        PersonalRating personalRating, RatingStatistics.Available statistics) {}
