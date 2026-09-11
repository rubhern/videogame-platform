package com.videogameplatform.ratings.application;

public final class RatingAlreadyExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingAlreadyExistsException() {
        super("An active rating already exists in the authenticated scope");
    }
}
