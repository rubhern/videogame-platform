package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** At most one pending window per existing periodic policy, never a general work queue. */
public interface ScheduledSynchronizationStore {
    enum Policy {
        NEAR_TERM,
        UPCOMING
    }

    record Pending(
            Policy policy, UUID requestId, CatalogueSynchronizationRequest window, Instant dueAt) {}

    void defer(Policy policy, CatalogueSynchronizationRequest window, Instant dueAt);

    List<Pending> pending();

    default Optional<Pending> next() {
        return pending().stream().findFirst();
    }

    void complete(Pending pending);

    void cancel(Policy policy);
}
