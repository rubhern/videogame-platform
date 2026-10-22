package com.videogameplatform.catalogue.application.synchronization;

import java.util.Locale;

/**
 * Whether a finished run needs an operator, derived only from its report.
 *
 * <p>It never changes the outcome, the outcome code, the counters or coverage: a run with failed
 * Games stays {@code PARTIAL} and keeps them counted. It only separates runs that read their whole
 * interval and lost nothing but known data-quality Games from runs that something interrupted,
 * that failed systemically, or whose failures are not understood. The vocabulary is closed, so it
 * can label a metric.
 */
public enum SynchronizationAssessment {
    /** Every Game was accounted for without failure. */
    CLEAN,
    /**
     * The whole interval was read and covered; every failed Game failed with a known, isolated
     * data-quality reason. Informational: no operator action.
     */
    DATA_QUALITY,
    /** An operator stopped the run at a safe point. Deliberate, not an incident. */
    STOPPED,
    /** Nothing ran: synchronization is disabled or another run owns the provider. Expected. */
    SKIPPED,
    /**
     * Anything else that finished: an interrupted interval, a failed run, a lost ownership, or a
     * failed Game whose reason is not a known data-quality failure. An operator should look.
     */
    ATTENTION;

    private static final String COMPLETED_WITH_FAILURES = "SYNCHRONIZATION_COMPLETED_WITH_FAILURES";
    private static final String STOPPED_CODE = "SYNCHRONIZATION_STOPPED";

    public static SynchronizationAssessment of(CatalogueSynchronizationReport report) {
        if (report.outcome() == SynchronizationOutcome.SKIPPED) {
            return SKIPPED;
        }
        if (STOPPED_CODE.equals(report.outcomeCode())) {
            return STOPPED;
        }
        if (report.outcome() == SynchronizationOutcome.SUCCEEDED) {
            return CLEAN;
        }
        var counters = report.counters();
        boolean onlyDataQuality =
                counters.failedGames() > 0
                        && counters.failedGames() == counters.dataQualityFailedGames();
        if (report.outcome() == SynchronizationOutcome.PARTIAL
                && COMPLETED_WITH_FAILURES.equals(report.outcomeCode())
                && onlyDataQuality) {
            return DATA_QUALITY;
        }
        return ATTENTION;
    }

    /** The lower-case form used as a metric tag and log value. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
