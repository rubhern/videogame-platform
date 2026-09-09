package com.videogameplatform.catalogue.adapter.provider.igdb;

import java.util.concurrent.locks.LockSupport;

/**
 * Spaces IGDB requests to the approved local rate.
 *
 * <p>The limiter is process-local, which is enough because it bounds politeness, not correctness,
 * and because the database-enforced single-active-run invariant means at most one synchronization
 * is issuing provider requests at a time across every instance.
 */
final class IgdbRequestRateLimiter {

    private final long intervalNanos;
    private long nextRequestNanos;

    IgdbRequestRateLimiter(double requestsPerSecond) {
        if (requestsPerSecond <= 0 || requestsPerSecond > IgdbApiSettings.MAX_REQUESTS_PER_SECOND) {
            throw new IllegalArgumentException("Unsupported IGDB request rate");
        }
        this.intervalNanos = (long) (1_000_000_000d / requestsPerSecond);
    }

    synchronized void acquire() {
        long waitNanos = nextRequestNanos - System.nanoTime();
        if (waitNanos > 0) {
            LockSupport.parkNanos(waitNanos);
        }
        nextRequestNanos = Math.max(System.nanoTime(), nextRequestNanos) + intervalNanos;
    }
}
