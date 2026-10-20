package com.videogameplatform.catalogue.application.preferences;

/** A preferred-platform choice that names a platform the local taxonomy does not hold. */
public final class PlatformPreferenceInvalidException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PlatformPreferenceInvalidException() {
        super("A preferred platform is not part of the local taxonomy");
    }
}
