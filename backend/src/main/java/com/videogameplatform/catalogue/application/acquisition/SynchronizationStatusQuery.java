package com.videogameplatform.catalogue.application.acquisition;

/** The current run and bounded recent run history, read from local run evidence only. */
public interface SynchronizationStatusQuery {
    /** At most {@link #RECENT_RUNS} finished runs, whatever the retained history holds. */
    int RECENT_RUNS = 20;

    SynchronizationStatus status();
}
