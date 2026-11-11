package com.videogameplatform.catalogue.application.administration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface CatalogueTranslations {
    Coverage coverage();

    Execution execution();

    Execution control(Action action, @Nullable UUID observedExecution);

    enum Action {
        START,
        STOP,
        RESUME
    }

    enum State {
        NOT_STARTED,
        RUNNING,
        STOPPING,
        STOPPED,
        INTERRUPTED,
        COMPLETED,
        ATTENTION
    }

    enum Reason {
        BATCH_FAILED,
        REMAINING_FAILURES,
        WORKER_FAILED
    }

    record Coverage(Instant observedAt, List<CoverageRow> rows) {}

    record CoverageRow(
            String kind,
            String status,
            @Nullable String origin,
            boolean lastValidRetained,
            long items) {}

    record Execution(
            State state,
            @Nullable UUID id,
            String afterKind,
            @Nullable UUID afterId,
            @Nullable Long estimatedTotal,
            long processed,
            long succeeded,
            long skipped,
            long failed,
            @Nullable Instant startedAt,
            @Nullable Instant lastActivityAt,
            @Nullable Reason reason) {}

    final class Conflict extends RuntimeException {}
}
