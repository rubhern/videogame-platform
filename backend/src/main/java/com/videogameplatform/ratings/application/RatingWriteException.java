package com.videogameplatform.ratings.application;

public final class RatingWriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingWriteException(Throwable cause) {
        super("The rating command could not commit", cause);
    }
}
