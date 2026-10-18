package com.videogameplatform.catalogue.application.administration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** What an operator's synchronization request did; only {@link Accepted} started anything. */
public sealed interface SynchronizationRequestOutcome {

    /** The run holds synchronization ownership and continues outside the caller. */
    record Accepted(UUID runId, LocalDate from, LocalDate to, Instant startedAt)
            implements SynchronizationRequestOutcome {
        public Accepted {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    /**
     * The interval is reversed, longer than {@link
     * RequestCatalogueSynchronization#MAX_INTERVAL_DAYS} or outside the supported days; {@code
     * bound} names the date to correct.
     */
    record RangeInvalid(Bound bound) implements SynchronizationRequestOutcome {
        public RangeInvalid {
            Objects.requireNonNull(bound, "bound");
        }
    }

    /** Another run, from any trigger, holds the single synchronization ownership. */
    record AlreadyRunning() implements SynchronizationRequestOutcome {}

    /** This environment has no provider access, so no run can start. */
    record Disabled() implements SynchronizationRequestOutcome {}

    enum Bound {
        FROM,
        TO
    }
}
