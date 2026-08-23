package com.videogameplatform.catalogue.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe runtime configuration for catalogue release browsing. */
@Validated
@ConfigurationProperties("catalogue.releases")
record CatalogueReleaseProperties(
        @Min(1) @Max(60) int recentWindowMonths,
        @Min(1) @Max(60) int upcomingWindowMonths,
        @NotNull Duration freshnessThreshold) {

    CatalogueReleaseProperties {
        if (freshnessThreshold != null
                && (freshnessThreshold.isZero()
                        || freshnessThreshold.isNegative()
                        || freshnessThreshold.compareTo(Duration.ofDays(365)) > 0)) {
            throw new IllegalArgumentException(
                    "Catalogue freshness threshold must be between one second and 365 days");
        }
    }
}
