package com.videogameplatform.ratings.configuration;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded JDBC execution time for personal and aggregate rating operations. */
@Validated
@ConfigurationProperties("ratings.jdbc")
record RatingJdbcProperties(@NotNull Duration operationTimeout) {
    RatingJdbcProperties {
        if (operationTimeout != null
                && (operationTimeout.getNano() != 0
                        || operationTimeout.compareTo(Duration.ofSeconds(1)) < 0
                        || operationTimeout.compareTo(Duration.ofSeconds(60)) > 0)) {
            throw new IllegalArgumentException(
                    "Rating JDBC timeout must be a whole number of seconds between 1 and 60");
        }
    }

    int operationTimeoutSeconds() {
        return Math.toIntExact(operationTimeout.getSeconds());
    }
}
