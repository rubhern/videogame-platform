package com.videogameplatform.catalogue.application.acquisition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Release-date coverage of an inclusive range, as consecutive periods in date order.
 *
 * <p>A covered period was completely scanned by a synchronization run at a known time: every
 * provider page of that run's interval was read. That time is freshness evidence for a caller's own
 * presentation policy; it never claims that the provider has not changed since, nor that a Game was
 * not refreshed through another interval. A period without a last scan was never covered. A run
 * interrupted before its last page, or in which no Game succeeded, covers nothing; its evidence
 * stays in the retained run reports.
 *
 * @param continuesFrom the first day not answered when the answer reached its bound before {@code
 *     to}; the periods then end the day before it
 */
public record SynchronizationCoverage(
        LocalDate from, LocalDate to, List<Period> periods, Optional<LocalDate> continuesFrom) {
    public SynchronizationCoverage {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        periods = List.copyOf(periods);
        Objects.requireNonNull(continuesFrom, "continuesFrom");
    }

    /** An inclusive period with one coverage state; empty {@code lastScan} is never covered. */
    public record Period(LocalDate from, LocalDate to, Optional<LastScan> lastScan) {
        public Period {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException("Invalid coverage period");
            }
            Objects.requireNonNull(lastScan, "lastScan");
        }

        public boolean covered() {
            return lastScan.isPresent();
        }
    }

    /**
     * The latest run that completely scanned a period. Every provider fact it reconciled was
     * observed between {@code scanStartedAt} and {@code scanCompletedAt}. {@code failedGames} is that
     * run's count of Games that kept their last valid state instead; a failed Game has no known
     * date, so the count describes the whole run, not only this period, and zero is a clean scan.
     * {@code runId} correlates with run history only while that bounded history retains the run.
     */
    public record LastScan(
            UUID runId, Instant scanStartedAt, Instant scanCompletedAt, long failedGames) {
        public LastScan {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(scanStartedAt, "scanStartedAt");
            Objects.requireNonNull(scanCompletedAt, "scanCompletedAt");
            if (failedGames < 0) {
                throw new IllegalArgumentException("A failed Game count cannot be negative");
            }
        }

        public boolean clean() {
            return failedGames == 0;
        }
    }
}
