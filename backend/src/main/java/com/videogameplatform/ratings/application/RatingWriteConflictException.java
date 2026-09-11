package com.videogameplatform.ratings.application;

public final class RatingWriteConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingWriteConflictException() {
        super("The personal rating changed after it was read");
    }
}
