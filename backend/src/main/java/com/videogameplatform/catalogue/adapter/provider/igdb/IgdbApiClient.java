package com.videogameplatform.catalogue.adapter.provider.igdb;

import com.videogameplatform.catalogue.adapter.provider.igdb.model.IgdbTokenPayload;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated, rate-limited and bounded access to the IGDB API.
 *
 * <p>The client owns every provider-facing concern the rest of the code must not see: the Twitch
 * client-credentials token and its cache, the approved request rate, bounded retries, request
 * timeouts, and the translation of any transport failure into the stable
 * {@link ProviderFailureCode} vocabulary. No response body, URL or credential ever leaves it,
 * including through an exception message.
 *
 * <p>The JDK HTTP client is used directly, as in the accepted provider proof, so timeouts, retries
 * and the untrusted raw payload stay explicit instead of being handled by message converters.
 */
public final class IgdbApiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final IgdbApiSettings settings;
    private final IgdbRequestRateLimiter rateLimiter;

    private CachedToken token;

    public IgdbApiClient(
            HttpClient httpClient, ObjectMapper objectMapper, IgdbApiSettings settings) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.rateLimiter = new IgdbRequestRateLimiter(settings.requestsPerSecond());
    }

    /** Runs one APICalypse query and returns its raw body with the work it cost. */
    public ApiResponse query(String endpoint, String query) {
        Attempt attempt = new Attempt();
        String body = executeQuery(endpoint, query, attempt, true);
        return new ApiResponse(body, attempt.statistics());
    }

    private String executeQuery(
            String endpoint, String query, Attempt attempt, boolean mayRefresh) {
        String accessToken = accessToken(attempt);
        HttpRequest request =
                HttpRequest.newBuilder(settings.apiBaseUri().resolve(endpoint))
                        .timeout(settings.requestTimeout())
                        .header("Accept", "application/json")
                        .header("Client-ID", settings.clientId())
                        .header("Authorization", "Bearer " + accessToken)
                        .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8))
                        .build();

        HttpResponse<String> response =
                send(request, attempt, ProviderFailureCode.PROVIDER_UNAVAILABLE);
        if (isUnauthorized(response.statusCode())) {
            if (!mayRefresh) {
                throw failure(ProviderFailureCode.PROVIDER_AUTHENTICATION_FAILED, attempt);
            }
            // The cached application token expired earlier than announced; obtain a new one once.
            invalidateToken();
            attempt.retried();
            return executeQuery(endpoint, query, attempt, false);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(ProviderFailureCode.PROVIDER_RESPONSE_INVALID, attempt);
        }
        return response.body();
    }

    private synchronized String accessToken(Attempt attempt) {
        CachedToken cached = token;
        if (cached != null && cached.isUsable()) {
            return cached.value();
        }
        String body =
                "client_id="
                        + encode(settings.clientId())
                        + "&client_secret="
                        + encode(settings.clientSecret())
                        + "&grant_type=client_credentials";
        HttpRequest request =
                HttpRequest.newBuilder(settings.tokenUri())
                        .timeout(settings.requestTimeout())
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

        HttpResponse<String> response =
                send(request, attempt, ProviderFailureCode.PROVIDER_AUTHENTICATION_FAILED);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(ProviderFailureCode.PROVIDER_AUTHENTICATION_FAILED, attempt);
        }
        IgdbTokenPayload payload;
        try {
            payload = objectMapper.readValue(response.body(), IgdbTokenPayload.class);
        } catch (JacksonException exception) {
            throw failure(ProviderFailureCode.PROVIDER_AUTHENTICATION_FAILED, attempt);
        }
        if (payload == null || payload.accessToken() == null || payload.accessToken().isBlank()) {
            throw failure(ProviderFailureCode.PROVIDER_AUTHENTICATION_FAILED, attempt);
        }
        long lifetimeSeconds = payload.expiresIn() == null ? 0L : payload.expiresIn();
        CachedToken refreshed =
                new CachedToken(
                        payload.accessToken(),
                        Instant.now().plusSeconds(Math.max(0L, lifetimeSeconds - 60L)));
        token = refreshed;
        return refreshed.value();
    }

    private synchronized void invalidateToken() {
        token = null;
    }

    private HttpResponse<String> send(
            HttpRequest request, Attempt attempt, ProviderFailureCode exhaustedCode) {
        ProviderFailureCode lastCode = exhaustedCode;
        for (int retry = 0; retry <= settings.maxRetries(); retry++) {
            if (retry > 0) {
                attempt.retried();
                backoff(retry);
            }
            rateLimiter.acquire();
            long startedAt = System.nanoTime();
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                attempt.completed(startedAt);
                if (response.statusCode() == 429) {
                    lastCode = ProviderFailureCode.PROVIDER_RATE_LIMITED;
                    continue;
                }
                if (response.statusCode() >= 500) {
                    lastCode = ProviderFailureCode.PROVIDER_UNAVAILABLE;
                    continue;
                }
                return response;
            } catch (IOException exception) {
                attempt.completed(startedAt);
                lastCode = ProviderFailureCode.PROVIDER_UNAVAILABLE;
            } catch (InterruptedException exception) {
                attempt.completed(startedAt);
                Thread.currentThread().interrupt();
                throw failure(ProviderFailureCode.PROVIDER_UNAVAILABLE, attempt);
            }
        }
        throw failure(lastCode, attempt);
    }

    private void backoff(int retry) {
        long millis =
                Math.min(
                        10_000L,
                        settings.retryBackoff().toMillis() * (1L << Math.min(retry - 1, 8)));
        if (millis > 0) {
            LockSupport.parkNanos(Duration.ofMillis(millis).toNanos());
        }
    }

    private static boolean isUnauthorized(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    private static ProviderRequestException failure(ProviderFailureCode code, Attempt attempt) {
        return new ProviderRequestException(code, attempt.statistics());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ApiResponse(String body, ProviderCallStatistics statistics) {}

    /** Per-call accounting; it never crosses a thread and never becomes shared state. */
    private static final class Attempt {

        private int requests;
        private int retries;
        private long latencyMillis;

        private void completed(long startedNanos) {
            requests++;
            latencyMillis += Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        }

        private void retried() {
            retries++;
        }

        private ProviderCallStatistics statistics() {
            return new ProviderCallStatistics(requests, retries, latencyMillis);
        }
    }

    private record CachedToken(String value, Instant usableUntil) {

        private boolean isUsable() {
            return Instant.now().isBefore(usableUntil);
        }
    }
}
