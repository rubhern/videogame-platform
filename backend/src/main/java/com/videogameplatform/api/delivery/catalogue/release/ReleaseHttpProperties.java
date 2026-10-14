package com.videogameplatform.api.delivery.catalogue.release;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe public caching configuration for the release endpoint. */
@Validated
@ConfigurationProperties("api.releases")
public record ReleaseHttpProperties(@NotBlank String cacheControl) {
    public ReleaseHttpProperties {
        // Older deployments may still configure the catalogue-only freshness window. Scores
        // change independently: a stored discovery response must always validate before reuse.
        if (cacheControl != null && !cacheControl.isBlank()) {
            cacheControl =
                    java.util.Arrays.stream(cacheControl.split(","))
                            .map(String::trim)
                            .filter(
                                    value ->
                                            !value.matches(
                                                    "(?i)(?:s-maxage|max-age|stale-while-revalidate|stale-if-error)\\s*=.*"))
                            .collect(java.util.stream.Collectors.joining(", "));
            if (!java.util.Arrays.asList(cacheControl.split(",\\s*")).contains("no-cache")) {
                cacheControl =
                        cacheControl.isBlank() ? "public, no-cache" : cacheControl + ", no-cache";
            }
        }
    }
}
