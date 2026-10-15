package com.videogameplatform.identity.application;

/** The request no longer carries a session allowed to act as a product user. */
public final class CurrentUserUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CurrentUserUnavailableException() {
        super("No valid authenticated session");
    }
}
