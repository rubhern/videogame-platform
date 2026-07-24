package com.videogameplatform.tools.igdb.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExecutionStats {

    private final Instant startedAt = Instant.now();
    private Instant completedAt;
    private int requestCount;
    private int successfulRequestCount;
    private int retryCount;
    private int rateLimitResponseCount;
    private int errorCount;
    private final List<Long> latenciesMillis = new ArrayList<>();

    public synchronized void recordRequest(int statusCode, long latencyMillis) {
        requestCount++;
        latenciesMillis.add(latencyMillis);
        if (statusCode >= 200 && statusCode < 300) {
            successfulRequestCount++;
        } else {
            errorCount++;
        }
        if (statusCode == 429) {
            rateLimitResponseCount++;
        }
    }

    public synchronized void recordTechnicalError() {
        requestCount++;
        errorCount++;
    }

    public synchronized void recordRetry() {
        retryCount++;
    }

    public synchronized void complete() {
        completedAt = Instant.now();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public int requestCount() {
        return requestCount;
    }

    public int successfulRequestCount() {
        return successfulRequestCount;
    }

    public int retryCount() {
        return retryCount;
    }

    public int rateLimitResponseCount() {
        return rateLimitResponseCount;
    }

    public int errorCount() {
        return errorCount;
    }

    public List<Long> latenciesMillis() {
        return List.copyOf(latenciesMillis);
    }

    public long p95LatencyMillis() {
        if (latenciesMillis.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(latenciesMillis);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, index));
    }

    public double successRate() {
        return requestCount == 0 ? 0.0 : successfulRequestCount * 100.0 / requestCount;
    }
}
