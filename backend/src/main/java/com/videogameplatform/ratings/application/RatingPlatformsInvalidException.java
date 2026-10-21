package com.videogameplatform.ratings.application;

/** The command names a platform twice or more platforms than one rating may hold. */
public final class RatingPlatformsInvalidException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingPlatformsInvalidException(IllegalArgumentException cause) {
        super("Rating platforms must be unique and bounded", cause);
    }
}
