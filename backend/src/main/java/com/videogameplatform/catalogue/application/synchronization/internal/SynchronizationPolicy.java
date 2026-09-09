package com.videogameplatform.catalogue.application.synchronization.internal;

import java.time.Duration;

/** Internal page, aggregate and operational-history bounds; none limits total Games in a request. */
public record SynchronizationPolicy(
        int providerPageSize, int maxReleasesPerGame, int retainedRuns, Duration abandonRunAfter) {
    public SynchronizationPolicy {
        if (providerPageSize < 1
                || providerPageSize > 500
                || maxReleasesPerGame < 1
                || maxReleasesPerGame > 200
                || retainedRuns < 1
                || retainedRuns > 1000
                || abandonRunAfter == null
                || abandonRunAfter.isNegative()
                || abandonRunAfter.isZero()
                || abandonRunAfter.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid synchronization bounds");
        }
    }
}
