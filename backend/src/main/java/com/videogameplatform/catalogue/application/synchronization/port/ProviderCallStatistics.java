package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * Bounded provider work performed by one port call.
 *
 * <p>Returning the counters keeps request, retry and latency accounting explicit instead of hiding
 * it in mutable adapter state that a second instance could not observe.
 */
public record ProviderCallStatistics(int requests, int retries, long latencyMillis) {

    private static final ProviderCallStatistics NONE = new ProviderCallStatistics(0, 0, 0L);

    public ProviderCallStatistics {
        if (requests < 0 || retries < 0 || latencyMillis < 0) {
            throw new IllegalArgumentException("Provider call statistics cannot be negative");
        }
    }

    public static ProviderCallStatistics none() {
        return NONE;
    }

    public ProviderCallStatistics plus(ProviderCallStatistics other) {
        return new ProviderCallStatistics(
                requests + other.requests(),
                retries + other.retries(),
                latencyMillis + other.latencyMillis());
    }
}
