package com.videogameplatform.catalogue.adapter.provider.igdb;

import java.net.URI;
import java.time.Duration;

/**
 * Everything the IGDB adapter needs, already validated by configuration.
 *
 * <p>Platform and release-region taxonomy is not configured here: provider platforms and regions
 * cross the port as typed references keyed by their provider entity ID, and the store resolves them
 * to product identity, reusing a known reference or creating a new product entity. The adapter
 * never maps provider slugs or names to a fixed product allowlist (EXT-002, REL-005).
 */
public record IgdbApiSettings(
        String clientId,
        String clientSecret,
        URI tokenUri,
        URI apiBaseUri,
        Duration requestTimeout,
        double requestsPerSecond,
        int maxRetries,
        Duration retryBackoff,
        int maxReleasesPerGame) {

    /** The approved local request rate; the accepted provider evidence never exceeded it. */
    public static final double MAX_REQUESTS_PER_SECOND = 3.0d;

    public IgdbApiSettings {
        if (tokenUri == null || apiBaseUri == null) {
            throw new IllegalArgumentException("IGDB endpoints are required");
        }
        if (requestsPerSecond <= 0 || requestsPerSecond > MAX_REQUESTS_PER_SECOND) {
            throw new IllegalArgumentException(
                    "The IGDB request rate must be greater than 0 and at most "
                            + MAX_REQUESTS_PER_SECOND
                            + " requests per second");
        }
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException("IGDB retries must be between 0 and 5");
        }
        if (requestTimeout == null
                || requestTimeout.isNegative()
                || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(
                    "The IGDB request timeout must be between one second and one minute");
        }
        if (retryBackoff == null
                || retryBackoff.isNegative()
                || retryBackoff.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException(
                    "The IGDB retry backoff must be between zero and ten seconds");
        }
        if (maxReleasesPerGame < 1 || maxReleasesPerGame > 200) {
            throw new IllegalArgumentException(
                    "The IGDB release fan-out per game must be between 1 and 200");
        }
    }

    /** Credentials stay in backend secret configuration; without them no provider call happens. */
    public boolean isConfigured() {
        return clientId != null
                && !clientId.isBlank()
                && clientSecret != null
                && !clientSecret.isBlank();
    }
}
