package com.videogameplatform.catalogue.application.synchronization.port;

import java.util.Optional;

/**
 * Publication did not happen and the previous valid publication is still current.
 *
 * <p>The message stays a stable phrase and the reason a closed vocabulary: SQL, table names and
 * driver text never escape the adapter. When the store already knows the published Game it failed
 * on, it names that product identity.
 */
public final class SynchronizationWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;
    private final transient SynchronizedGameIdentity game;

    public SynchronizationWriteException(Reason reason, Throwable cause) {
        super(reason.message, cause);
        this.reason = reason;
        this.game = null;
    }

    public SynchronizationWriteException(Reason reason) {
        this(reason, (SynchronizedGameIdentity) null);
    }

    public SynchronizationWriteException(Reason reason, SynchronizedGameIdentity game) {
        super(reason.message);
        this.reason = reason;
        this.game = game;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<SynchronizedGameIdentity> game() {
        return Optional.ofNullable(game);
    }

    /**
     * Why the store refused or failed the write.
     *
     * <p>Datastore categories are distinguished only where the adapter can classify them
     * reliably; everything else is {@link #PERSISTENCE_WRITE_FAILED}.
     */
    public enum Reason {
        /** The run lost its heartbeat-owned lease; a successor may already own the provider. */
        OWNERSHIP_LOST("Synchronization ownership expired"),
        /** The published Game already holds more releases than the approved bound. */
        RELEASE_BOUND_EXCEEDED("Game exceeds release bound"),
        /** A statement or the write transaction exceeded its timeout. */
        PERSISTENCE_TIMEOUT("The catalogue synchronization write timed out"),
        /** The datastore rejected the data: a constraint or a column rule. */
        PERSISTENCE_CONSTRAINT_VIOLATION("The catalogue synchronization write was rejected"),
        /** No usable database connection or transaction could be obtained. */
        PERSISTENCE_CONNECTION_FAILED("The catalogue store was unavailable"),
        /** Any other datastore failure. */
        PERSISTENCE_WRITE_FAILED("The catalogue synchronization write failed");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
