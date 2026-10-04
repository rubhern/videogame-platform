package com.videogameplatform.catalogue.application.localization.port;

/** An ambiguous timeout keeps the durable claim until expiry instead of duplicating in-flight work. */
public final class TranslationFailure extends RuntimeException {
    private final boolean mayStillBeRunning;

    public TranslationFailure(boolean mayStillBeRunning, Throwable cause) {
        super("Catalogue translation failed", cause);
        this.mayStillBeRunning = mayStillBeRunning;
    }

    public boolean mayStillBeRunning() {
        return mayStillBeRunning;
    }
}
