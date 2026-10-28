package com.videogameplatform.catalogue.application.platformgames;

/** Stable application validation outcomes of a platform catalogue selection. */
public final class PlatformGamesQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    public PlatformGamesQueryException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        /** No platform, or one the product taxonomy does not hold. */
        PLATFORM_NOT_SUPPORTED,
        /** A genre the product taxonomy does not hold. */
        GENRE_NOT_SUPPORTED,
        /** A first year after the last one. */
        YEAR_RANGE_INVALID
    }
}
