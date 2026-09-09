package com.videogameplatform.catalogue.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe runtime configuration for UC-009.
 *
 * <p>The page size bounds individual provider transfers, never total Games in a request.
 * Provider credentials are injected from backend secret configuration and are blank by default, so
 * an unconfigured environment reports {@code SYNCHRONIZATION_DISABLED} instead of attempting a
 * call. They are never written to a log, a metric or an exposed Actuator endpoint.
 */
@Validated
@ConfigurationProperties("catalogue.synchronization")
record CatalogueSynchronizationProperties(
        @Min(1) @Max(500) int providerPageSize,
        @Min(1) @Max(200) int maxReleasesPerGame,
        @Min(1) @Max(1000) int retainedRuns,
        @NotNull Duration abandonRunAfter,
        @NotNull @Valid Cover cover,
        @NotNull @Valid Provider provider) {

    /** The product-owned cover a game falls back to when no valid provider cover exists. */
    record Cover(@NotBlank String fallbackAssetPath, @NotBlank String fallbackSourceName) {}

    /** IGDB access, its approved local bounds, and the product-owned provider taxonomy mapping. */
    record Provider(
            String clientId,
            String clientSecret,
            @NotNull URI tokenUri,
            @NotNull URI apiBaseUri,
            @NotNull Duration requestTimeout,
            double requestsPerSecond,
            @Min(0) @Max(5) int maxRetries,
            @NotNull Duration retryBackoff,
            @NotNull Map<String, String> platformCodes,
            @NotNull Map<String, String> regionCodes,
            @NotBlank String unknownRegionCode) {}
}
