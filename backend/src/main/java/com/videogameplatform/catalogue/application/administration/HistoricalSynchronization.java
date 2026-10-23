package com.videogameplatform.catalogue.application.administration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One durable operator-controlled historical target; never auto-resumed on startup. */
public interface HistoricalSynchronization {
    Status status();

    Status start(LocalDate from, LocalDate to, CoveredMonths coveredMonths);

    Status stop(UUID id);

    Status resume(UUID id);

    enum CoveredMonths {
        SKIP,
        RERUN
    }

    enum State {
        NOT_STARTED,
        RUNNING,
        WAITING,
        STOPPING,
        STOPPED,
        INTERRUPTED,
        COMPLETED,
        ATTENTION
    }

    enum Rejection {
        RANGE_INVALID,
        CONFLICT,
        NOT_FOUND
    }

    final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Rejection reason;

        public Rejected(Rejection reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Rejection reason() {
            return reason;
        }
    }

    /** Constant-size local snapshot. Totals count completed attempts, not unique identities. */
    record Status(
            UUID id,
            UUID executionId,
            State state,
            LocalDate from,
            LocalDate to,
            CoveredMonths coveredMonths,
            LocalDate nextFrom,
            LocalDate currentTo,
            UUID runId,
            int totalMonths,
            int completedMonths,
            int skippedMonths,
            long processedGames,
            long processedReleases,
            long dataQualityFailedGames,
            Long currentGames,
            Long currentReleases,
            Long currentFailedGames,
            Long currentDataQualityFailedGames,
            Long currentProviderRequests,
            Instant startedAt,
            Instant lastActivityAt,
            long elapsedSeconds,
            String lastReason) {
        public static Status notStarted() {
            return new Status(
                    null,
                    null,
                    State.NOT_STARTED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    null);
        }

        public int remainingMonths() {
            return totalMonths - completedMonths;
        }

        public int progressPercent() {
            return totalMonths == 0 ? 0 : completedMonths * 100 / totalMonths;
        }
    }
}
