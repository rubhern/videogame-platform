package com.videogameplatform.platform.observability;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe execution bound for the catalogue-store readiness probe. */
@Validated
@ConfigurationProperties("platform.readiness")
record ReadinessHealthProperties(@NotNull Duration catalogueStoreQueryTimeout) {

    ReadinessHealthProperties {
        if (catalogueStoreQueryTimeout != null
                && (catalogueStoreQueryTimeout.getNano() != 0
                        || catalogueStoreQueryTimeout.compareTo(Duration.ofSeconds(1)) < 0
                        || catalogueStoreQueryTimeout.compareTo(Duration.ofSeconds(10)) > 0)) {
            throw new IllegalArgumentException(
                    "Catalogue-store readiness timeout must be a whole number of seconds between 1 and 10");
        }
    }

    int catalogueStoreQueryTimeoutSeconds() {
        return Math.toIntExact(catalogueStoreQueryTimeout.getSeconds());
    }
}
