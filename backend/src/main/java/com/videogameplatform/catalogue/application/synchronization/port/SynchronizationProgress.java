package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator-facing progress of synchronization runs.
 *
 * <p>It observes and never influences a run: the durable report and the metrics stay the record of
 * what happened. Every value is bounded: counters, page positions, closed stage/reason codes, and
 * the product Game identity (identifier and slug) once one exists. Titles, provider identifiers
 * and payloads never cross this port. Implementations must not throw.
 */
public interface SynchronizationProgress {

    /** Discards every event. */
    SynchronizationProgress NONE =
            new SynchronizationProgress() {
                @Override
                public Run started(
                        UUID runId,
                        CatalogueSynchronizationRequest request,
                        Instant startedAt,
                        int providerPageSize) {
                    return Run.NONE;
                }

                @Override
                public void skipped(CatalogueSynchronizationReport report) {}
            };

    /** A run acquired its lease; the returned tracker belongs to this run alone. */
    Run started(
            UUID runId,
            CatalogueSynchronizationRequest request,
            Instant startedAt,
            int providerPageSize);

    /** The terminal event of a request that never acquired a run. */
    void skipped(CatalogueSynchronizationReport report);

    /** Events of one started run, all emitted from the thread that executes it. */
    interface Run {

        Run NONE =
                new Run() {
                    @Override
                    public void pageRequested(int page, Counters counters) {}

                    @Override
                    public void pageFetched(int page, int games, Counters counters) {}

                    @Override
                    public void gameSucceeded(
                            int page, int position, GameResult result, Counters counters) {}

                    @Override
                    public void gameFailed(
                            int page,
                            int position,
                            Failure failure,
                            Optional<SynchronizedGameIdentity> game,
                            Counters counters) {}

                    @Override
                    public void pageCompleted(int page, Counters counters) {}

                    @Override
                    public void runFailed(Failure failure, Counters counters) {}

                    @Override
                    public void finished(CatalogueSynchronizationReport report) {}
                };

        /** A provider page request is about to start. */
        void pageRequested(int page, Counters counters);

        /** The provider page listed {@code games} Games to reconcile. */
        void pageFetched(int page, int games, Counters counters);

        /** One Game was reconciled, or deferred by import policy, at a one-based page position. */
        void gameSucceeded(int page, int position, GameResult result, Counters counters);

        /**
         * One Game failed in isolation; the run continues.
         *
         * <p>{@code game} is present once product identity exists: published, or assigned by this
         * attempt and rolled back with it. It is empty when the failure precedes both.
         */
        void gameFailed(
                int page,
                int position,
                Failure failure,
                Optional<SynchronizedGameIdentity> game,
                Counters counters);

        /** Every Game of the page was accounted for and the lease heartbeat succeeded. */
        void pageCompleted(int page, Counters counters);

        /** A failure that ends the run before the window was traversed. */
        void runFailed(Failure failure, Counters counters);

        /** The terminal event of the run, after its durable report was recorded. */
        void finished(CatalogueSynchronizationReport report);
    }

    /** How one Game was accounted for. */
    enum GameResult {
        CREATED,
        UPDATED,
        UNCHANGED,
        DEFERRED
    }

    /** The processing stage a failure belongs to. */
    enum Stage {
        /** Listing the Games of the requested window. */
        PROVIDER_PAGE,
        /** Fetching one Game and its releases. */
        PROVIDER_GAME,
        /** The normalized provider record is not publishable. */
        VALIDATION,
        /** Reconciling the record against the published Game. */
        RECONCILIATION,
        /** Reading, writing, fencing or completing through the store. */
        PERSISTENCE,
        /** Anything outside the reviewed failure vocabulary. */
        RUN
    }

    /**
     * A stage and a stable reason from a closed vocabulary: a {@link ProviderFailureCode}, a {@link
     * ProviderMappingFailure}, a {@link SynchronizationWriteException.Reason}, or a reason constant
     * named by the synchronization use case. {@code failureType} names the exception class of an
     * unexpected failure only.
     */
    record Failure(Stage stage, String reason, Optional<String> failureType) {

        public static Failure of(Stage stage, String reason) {
            return new Failure(stage, reason, Optional.empty());
        }
    }
}
