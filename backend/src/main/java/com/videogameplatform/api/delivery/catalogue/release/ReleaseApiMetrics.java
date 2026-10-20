package com.videogameplatform.api.delivery.catalogue.release;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for the release query: page yield per closed sort vocabulary.
 */
@Component
final class ReleaseApiMetrics {

    private static final Set<String> SORTS =
            Set.of("releaseDate", "hype", "rating", "externalPlayerScore");

    private final MeterRegistry registry;

    ReleaseApiMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String sort : SORTS) {
            summary(sort);
        }
    }

    void recordResult(String sort, int resultCount) {
        summary(SORTS.contains(sort) ? sort : "invalid").record(resultCount);
    }

    private DistributionSummary summary(String sort) {
        return DistributionSummary.builder("catalogue.releases.result.count")
                .description("Number of release items returned by a successful request")
                .tag("sort", sort)
                .register(registry);
    }
}
