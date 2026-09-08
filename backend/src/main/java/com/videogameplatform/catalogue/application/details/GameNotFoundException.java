package com.videogameplatform.catalogue.application.details;

public final class GameNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GameNotFoundException() {
        super("Game is not in the current local catalogue");
    }
}
