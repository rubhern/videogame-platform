package com.videogameplatform.catalogue.application.synchronization;

import java.util.Objects;

/** The answer to taking synchronization ownership, known before any provider work. */
public sealed interface SynchronizationStart {

    /** Ownership was taken; the caller decides on which thread the run executes. */
    record Started(OwnedRun run) implements SynchronizationStart {
        public Started {
            Objects.requireNonNull(run, "run");
        }
    }

    /** Nothing started and no local state changed; {@code report} is the skipped report. */
    record Skipped(Reason reason, CatalogueSynchronizationReport report)
            implements SynchronizationStart {
        public Skipped {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(report, "report");
        }
    }

    enum Reason {
        /** No provider access is configured ({@code SYNCHRONIZATION_DISABLED}). */
        DISABLED,
        /** Another run holds the ownership ({@code SYNCHRONIZATION_ALREADY_RUNNING}). */
        ALREADY_RUNNING
    }
}
