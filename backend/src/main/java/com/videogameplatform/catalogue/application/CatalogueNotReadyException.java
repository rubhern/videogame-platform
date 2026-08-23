package com.videogameplatform.catalogue.application;

/** Signals that no valid current local catalogue publication exists yet. */
public final class CatalogueNotReadyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CatalogueNotReadyException() {
        super("No valid local catalogue snapshot has been published yet");
    }
}
