package com.videogameplatform.catalogue.configuration;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe JDBC limits for catalogue reads. */
@Validated
@ConfigurationProperties("catalogue.jdbc")
record CatalogueJdbcProperties(@NotNull Duration readTimeout) {

    CatalogueJdbcProperties {
        if (readTimeout != null
                && (readTimeout.getNano() != 0
                        || readTimeout.compareTo(Duration.ofSeconds(1)) < 0
                        || readTimeout.compareTo(Duration.ofSeconds(60)) > 0)) {
            throw new IllegalArgumentException(
                    "Catalogue JDBC read timeout must be a whole number of seconds between 1 and 60");
        }
    }

    int readTimeoutSeconds() {
        return Math.toIntExact(readTimeout.getSeconds());
    }
}
