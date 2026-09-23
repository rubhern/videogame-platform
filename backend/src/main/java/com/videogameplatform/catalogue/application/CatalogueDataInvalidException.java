package com.videogameplatform.catalogue.application;

/** Signals that persisted local catalogue data cannot be interpreted safely. */
public final class CatalogueDataInvalidException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CatalogueDataInvalidException(Throwable cause) {
        super("Persisted local catalogue data is invalid", cause);
    }
}
