package com.videogameplatform.catalogue.application.administration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** What an operator's stop request did; only {@link Requested} recorded anything. */
public sealed interface StopRequestOutcome {

    /**
     * The run is still in flight with a recorded stop request; {@code stopRequestedAt} is the first
     * request's time, also when this one repeated it.
     */
    record Requested(UUID runId, Instant stopRequestedAt) implements StopRequestOutcome {
        public Requested {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(stopRequestedAt, "stopRequestedAt");
        }
    }

    /** Run history holds no such run of this provider. */
    record NotFound() implements StopRequestOutcome {}

    /** The run already finished and no longer holds ownership. */
    record Finished() implements StopRequestOutcome {}
}
