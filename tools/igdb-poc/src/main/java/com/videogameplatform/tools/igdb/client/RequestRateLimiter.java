package com.videogameplatform.tools.igdb.client;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

public final class RequestRateLimiter {

    private final long intervalNanos;
    private long nextRequestNanos;

    public RequestRateLimiter(double requestsPerSecond) {
        if (requestsPerSecond <= 0 || requestsPerSecond > 3) {
            throw new IllegalArgumentException("requests-per-second must be greater than 0 and at most 3");
        }
        intervalNanos = (long) (1_000_000_000D / requestsPerSecond);
    }

    public synchronized void acquire() {
        long now = System.nanoTime();
        long waitNanos = nextRequestNanos - now;
        if (waitNanos > 0) {
            LockSupport.parkNanos(waitNanos);
        }
        nextRequestNanos = Math.max(System.nanoTime(), nextRequestNanos) + intervalNanos;
    }

    static Duration retryDelay(int retryNumber) {
        long millis = Math.min(2_000L, 250L * (1L << Math.max(0, retryNumber - 1)));
        return Duration.ofMillis(millis);
    }
}
