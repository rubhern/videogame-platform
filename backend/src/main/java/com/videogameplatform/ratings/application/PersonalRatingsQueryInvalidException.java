package com.videogameplatform.ratings.application;

public final class PersonalRatingsQueryInvalidException extends RuntimeException {
    public enum Field {
        SEARCH,
        SORT,
        DIRECTION,
        PAGINATION
    }

    private final Field field;

    public PersonalRatingsQueryInvalidException(Field field) {
        super("Invalid personal ratings query");
        this.field = field;
    }

    public Field field() {
        return field;
    }
}
