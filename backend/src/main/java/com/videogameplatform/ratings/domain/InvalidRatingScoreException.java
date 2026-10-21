package com.videogameplatform.ratings.domain;

/** A rating score that breaks RAT-011 or RAT-012; nothing built from it may be stored. */
public final class InvalidRatingScoreException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public enum Violation {
        /** A platform appears twice, or more than {@link RatingScore#MAX_PLATFORMS} appear. */
        PLATFORMS,
        /** Neither or both of a direct value and a scored platform are present. */
        SCORE_SOURCE
    }

    private final Violation violation;

    InvalidRatingScoreException(Violation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public Violation violation() {
        return violation;
    }
}
