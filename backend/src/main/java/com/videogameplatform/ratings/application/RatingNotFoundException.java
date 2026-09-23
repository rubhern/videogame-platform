package com.videogameplatform.ratings.application;

public final class RatingNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingNotFoundException() {
        super("No active rating exists in the authenticated scope");
    }
}
