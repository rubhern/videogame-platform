package com.videogameplatform.ratings.application;

/**
 * Signals that the current user's personal ratings collection could not be read. It is distinct
 * from {@link PersonalRatingReadException} because the collection resource exposes its own stable
 * failure code and never degrades to the generic internal error.
 */
public final class PersonalRatingsReadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PersonalRatingsReadException(Throwable cause) {
        super("The personal ratings collection could not be read", cause);
    }
}
