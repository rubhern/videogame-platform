package com.videogameplatform.api.delivery.catalogue.release;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for the release query.
 */
@Component
final class ReleaseApiMetrics {

    private final MeterRegistry registry;

    ReleaseApiMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String view : new String[] {"recent", "upcoming"}) {
            DistributionSummary.builder("catalogue.releases.result.count")
                    .description("Number of release items returned by a successful request")
                    .tag("view", view)
                    .register(registry);
        }
    }

    void recordResult(String view, int resultCount) {
        String safeView = safeView(view);
        DistributionSummary.builder("catalogue.releases.result.count")
                .description("Number of release items returned by a successful request")
                .tag("view", safeView)
                .register(registry)
                .record(resultCount);
    }

    private static String safeView(String view) {
        if (view == null) {
            return "invalid";
        }
        String normalized = view.toLowerCase(Locale.ROOT);
        return "recent".equals(normalized) || "upcoming".equals(normalized)
                ? normalized
                : "invalid";
    }
}
