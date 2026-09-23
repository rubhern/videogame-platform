package com.videogameplatform.catalogue.application.synchronization;

/** Final state of one synchronization run. */
public enum SynchronizationOutcome {
    /** Every Game in the requested interval was accounted for without failure. */
    SUCCEEDED,
    /** Part of the interval succeeded; failed Games kept their previous valid data. */
    PARTIAL,
    /** Nothing was published; the previous valid publication still serves every read. */
    FAILED,
    /** The run did not start, so no local state changed. */
    SKIPPED,
    /** A run is still in flight; only a recorded run can report this. */
    RUNNING
}
