package com.videogameplatform.catalogue.application.acquisition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The run in flight, if any, and the most recent finished runs that bounded run history retains,
 * newest first.
 *
 * <p>This is operational history, not coverage: a failed, interrupted or abandoned run never
 * changes what {@link SynchronizationCoverage} reports, and a run pruned from history keeps the
 * coverage it recorded.
 */
public record SynchronizationStatus(Optional<CurrentRun> currentRun, List<FinishedRun> recentRuns) {
    public SynchronizationStatus {
        Objects.requireNonNull(currentRun, "currentRun");
        recentRuns = List.copyOf(recentRuns);
    }

    /**
     * A run that holds the provider's run ownership. {@code stalled} means its worker has not
     * proven it is alive within the abandonment period, so an operator may abandon it and the next
     * run start will otherwise record it as abandoned; until then nothing else can run. {@code
     * stopRequestedAt} is present once an operator requested its stop, which its worker has not
     * reached a safe point to act on yet.
     */
    public record CurrentRun(
            UUID runId,
            LocalDate from,
            LocalDate to,
            Instant startedAt,
            Instant lastHeartbeatAt,
            boolean stalled,
            Optional<Instant> stopRequestedAt) {
        public CurrentRun {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
            Objects.requireNonNull(stopRequestedAt, "stopRequestedAt");
        }
    }

    /**
     * A finished run and its stable outcome code. Counters are absent for an abandoned run, whose
     * worker never reported them; they are never invented as zero. {@code stopRequestedAt} is
     * present when an operator requested its stop, whether or not the run reached a safe point.
     */
    public record FinishedRun(
            UUID runId,
            LocalDate from,
            LocalDate to,
            Instant startedAt,
            Instant completedAt,
            Result result,
            String outcomeCode,
            Optional<Counters> counters,
            Optional<Instant> stopRequestedAt) {
        public FinishedRun {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(outcomeCode, "outcomeCode");
            Objects.requireNonNull(counters, "counters");
            Objects.requireNonNull(stopRequestedAt, "stopRequestedAt");
        }
    }

    /**
     * {@code PARTIAL} committed some Games: the whole interval was scanned with failed Games, or the
     * run was interrupted or stopped by an operator, which its outcome code distinguishes; a stopped
     * run that committed nothing is {@code FAILED}. {@code ABANDONED} lost its worker before
     * finishing and was closed by a later run start or an operator, which its outcome code
     * distinguishes too.
     */
    public enum Result {
        SUCCEEDED,
        PARTIAL,
        FAILED,
        ABANDONED
    }

    /** Bounded Game, Release and provider-request counts the finished run reported. */
    public record Counters(
            long createdGames,
            long updatedGames,
            long unchangedGames,
            long deferredGames,
            long failedGames,
            long createdReleases,
            long updatedReleases,
            long unchangedReleases,
            long deletedReleases,
            long providerRequests,
            long providerRetries) {}
}
