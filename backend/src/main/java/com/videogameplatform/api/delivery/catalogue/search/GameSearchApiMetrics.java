package com.videogameplatform.api.delivery.catalogue.search;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for catalogue search.
 *
 * <p>A zero-result search is a product signal worth watching, so the outcome is counted. The
 * visitor's query text is never stored, logged or used as a metric dimension: only the closed
 * outcome vocabulary is exposed.
 */
@Component
final class GameSearchApiMetrics {

    private static final String OUTCOME_METER = "catalogue.search.result.outcome";
    private static final String ZERO_RESULTS = "zero_results";
    private static final String RESULTS = "results";

    private final Counter zeroResults;
    private final Counter results;

    GameSearchApiMetrics(MeterRegistry registry) {
        this.zeroResults = counter(registry, ZERO_RESULTS);
        this.results = counter(registry, RESULTS);
    }

    void recordResult(long totalItems) {
        if (totalItems == 0) {
            zeroResults.increment();
        } else {
            results.increment();
        }
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(OUTCOME_METER)
                .description("Catalogue searches by bounded outcome, without any query content")
                .tag("outcome", outcome)
                .register(registry);
    }
}
