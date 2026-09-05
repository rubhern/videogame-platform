package com.videogameplatform.catalogue.application.releases;

/** Stable application validation outcomes for product-owned taxonomies. */
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
        REGION_NOT_SUPPORTED
    }
}
