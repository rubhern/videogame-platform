package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * Publication did not happen and the previous valid publication is still current.
 *
 * <p>The message stays a stable phrase: SQL, table names and driver text never escape the adapter.
 */
public final class SynchronizationWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SynchronizationWriteException(Throwable cause) {
        super("The catalogue synchronization write failed", cause);
    }

    public SynchronizationWriteException(String reason) {
        super(reason);
    }
}
