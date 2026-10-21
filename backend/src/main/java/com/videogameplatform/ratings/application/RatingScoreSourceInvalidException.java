package com.videogameplatform.ratings.application;

/**
 * The resulting rating would have no score source, or both a direct value and scored platforms.
 */
public final class RatingScoreSourceInvalidException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingScoreSourceInvalidException(IllegalArgumentException cause) {
        super("A rating has a direct value exactly when no platform carries a score", cause);
    }
}
