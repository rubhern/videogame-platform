package com.videogameplatform.ratings.application;

public final class RatingValueInvalidException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Integer platformIndex;

    public RatingValueInvalidException(IllegalArgumentException cause) {
        this(cause, null);
    }

    /**
     * @param platformIndex the position of the offending platform in the command, or null when
     *     the direct value is invalid
     */
    public RatingValueInvalidException(IllegalArgumentException cause, Integer platformIndex) {
        super("Rating value must be an integer from 1 through 10", cause);
        this.platformIndex = platformIndex;
    }

    public Integer platformIndex() {
        return platformIndex;
    }
}
