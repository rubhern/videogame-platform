package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.CoveredMonths;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.State;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.Status;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL target, checkpoint and cross-instance orchestration exclusion. */
public interface HistoricalBackfillStore {
    Optional<Lease> acquire();

    Optional<Lease> acquireScheduledTurn();

    boolean scheduledTurnPending();

    Status status(Instant now);

    void create(UUID id, LocalDate from, LocalDate to, CoveredMonths coveredMonths, Instant now);

    void resume(UUID id, Instant now);

    void stop(UUID id, Instant now);

    void state(UUID id, UUID executionId, State state, String reason, Instant now);

    void attach(UUID id, UUID executionId, UUID runId, LocalDate to, Instant now);

    void advance(
            UUID id,
            UUID executionId,
            LocalDate next,
            boolean skipped,
            CatalogueSynchronizationReport report,
            Instant now);

    boolean covered(LocalDate from, LocalDate to);

    void progress(UUID runId, CatalogueSynchronizationReport.Counters counters, Instant now);

    /** Session advisory lock released even on process death; no database transaction spans work. */
    interface Lease extends AutoCloseable {
        void check();

        @Override
        void close();
    }
}
