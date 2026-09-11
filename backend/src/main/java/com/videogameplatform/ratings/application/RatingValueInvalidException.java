package com.videogameplatform.ratings.application;

public final class RatingValueInvalidException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingValueInvalidException(IllegalArgumentException cause) {
        super("Rating value must be an integer from 1 through 10", cause);
    }
}
