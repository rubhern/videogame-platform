package com.videogameplatform.tools.igdb.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.locks.LockSupport;

import com.videogameplatform.tools.igdb.model.ExecutionStats;
import com.videogameplatform.tools.igdb.support.PocException;

public final class HttpCallExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient client;
    private final ExecutionStats stats;

    public HttpCallExecutor(HttpClient client, ExecutionStats stats) {
        this.client = client;
        this.stats = stats;
    }

    public HttpResponse<String> execute(HttpRequest request, Runnable beforeAttempt) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            beforeAttempt.run();
            long started = System.nanoTime();
            try {
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString());
                stats.recordRequest(response.statusCode(), elapsedMillis(started));
                if (!retryable(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                    return response;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                stats.recordTechnicalError();
                throw new PocException("HTTP request interrupted", exception);
            } catch (IOException exception) {
                stats.recordTechnicalError();
                if (attempt == MAX_ATTEMPTS) {
                    throw new PocException("HTTP request failed after bounded retries", exception);
                }
            }
            stats.recordRetry();
            LockSupport.parkNanos(RequestRateLimiter.retryDelay(attempt).toNanos());
        }
        throw new PocException("HTTP request failed");
    }

    private boolean retryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
