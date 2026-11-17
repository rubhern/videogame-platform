package com.videogameplatform.api.delivery.catalogue.platformgames;

import com.videogameplatform.api.generated.model.PlatformGamesSort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for Explorar catálogo: served pages per closed sort and outcome.
 * Platforms, genres, queries, game identifiers and page numbers never become dimensions.
 */
@Component
final class PlatformGamesApiMetrics {

    private static final String METER = "catalogue.platform_games.page.outcome";

    private final Map<String, Counter> counters;

    PlatformGamesApiMetrics(MeterRegistry registry) {
        Map<String, Counter> bySortAndOutcome = new HashMap<>();
        for (PlatformGamesSort sort : PlatformGamesSort.values()) {
            bySortAndOutcome.put(key(sort, true), counter(registry, sort, "items"));
            bySortAndOutcome.put(key(sort, false), counter(registry, sort, "empty"));
        }
        this.counters = Map.copyOf(bySortAndOutcome);
    }

    void recordPage(PlatformGamesSort sort, int itemCount) {
        Counter counter = counters.get(key(sort, itemCount > 0));
        if (counter != null) {
            counter.increment();
        }
    }

    private static String key(PlatformGamesSort sort, boolean items) {
        return sort.getValue() + (items ? ":items" : ":empty");
    }

    private static Counter counter(MeterRegistry registry, PlatformGamesSort sort, String outcome) {
        return Counter.builder(METER)
                .description("Platform catalogue pages served by bounded sort and outcome")
                .tag("sort", sort.getValue())
                .tag("outcome", outcome)
                .register(registry);
    }
}
