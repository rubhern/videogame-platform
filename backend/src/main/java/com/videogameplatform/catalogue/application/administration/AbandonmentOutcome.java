package com.videogameplatform.catalogue.application.administration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** What an operator's abandonment did; only {@link Abandoned} changed anything. */
public sealed interface AbandonmentOutcome {

    /** The outcome code an operator's abandonment records, distinct from a run start's. */
    String ABANDONED_BY_OPERATOR = "SYNCHRONIZATION_ABANDONED_BY_OPERATOR";

    /** The run is finished as abandoned and fenced; ownership is free for the next run. */
    record Abandoned(
            UUID runId,
            LocalDate from,
            LocalDate to,
            Instant startedAt,
            Instant completedAt,
            String outcomeCode)
            implements AbandonmentOutcome {
        public Abandoned {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(outcomeCode, "outcomeCode");
        }
    }

    /** Run history holds no such run of this provider. */
    record NotFound() implements AbandonmentOutcome {}

    /** The run already finished, abandoned included, and no longer holds ownership. */
    record Finished() implements AbandonmentOutcome {}

    /** The worker gave a heartbeat within the abandonment period, so it still owns the run. */
    record NotStale(Instant lastHeartbeatAt) implements AbandonmentOutcome {
        public NotStale {
            Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
        }
    }
}
