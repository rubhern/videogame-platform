package com.videogameplatform.catalogue.application.releases;

/** Stable application validation outcomes for product-owned taxonomies and the date range. */
public final class ReleaseQueryValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    public ReleaseQueryValidationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        PLATFORM_NOT_SUPPORTED,
        REGION_NOT_SUPPORTED,
        /** {@code to} without {@code from}. */
        RANGE_START_MISSING,
        /** {@code from} without {@code to}. */
        RANGE_END_MISSING,
        /** An end before the start, or a range longer than the maximum. */
        RANGE_INVALID,
        /** A calendar month before the current one or beyond the forward bound. */
        MONTH_OUT_OF_RANGE
    }
}
