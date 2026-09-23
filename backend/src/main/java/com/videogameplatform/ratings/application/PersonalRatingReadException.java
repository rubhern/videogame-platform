package com.videogameplatform.ratings.application;

public final class PersonalRatingReadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PersonalRatingReadException(Throwable cause) {
        super("The personal rating could not be read", cause);
    }
}
