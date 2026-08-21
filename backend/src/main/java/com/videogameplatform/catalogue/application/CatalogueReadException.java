package com.videogameplatform.catalogue.application;

/** Signals a safe, provider-independent failure while reading the local catalogue. */
public final class CatalogueReadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CatalogueReadException(Throwable cause) {
        super("Local catalogue data cannot currently be read", cause);
    }
}
