package com.videogameplatform.api.delivery.catalogue.ranking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for TOPómetro: served pages per closed source and outcome. Game
 * identifiers, positions and page numbers never become dimensions.
 */
@Component
final class GameRankingApiMetrics {

    private static final String METER = "catalogue.rankings.page.outcome";
    private static final List<String> SOURCES = List.of("igdb_players", "gameometro_community");

    private final Map<String, Counter> counters;

    GameRankingApiMetrics(MeterRegistry registry) {
        Map<String, Counter> bySourceAndOutcome = new HashMap<>();
        for (String source : SOURCES) {
            bySourceAndOutcome.put(key(source, true), counter(registry, source, "items"));
            bySourceAndOutcome.put(key(source, false), counter(registry, source, "empty"));
        }
        this.counters = Map.copyOf(bySourceAndOutcome);
    }

    void recordPage(String source, int itemCount) {
        Counter counter = counters.get(key(source, itemCount > 0));
        if (counter != null) {
            counter.increment();
        }
    }

    private static String key(String source, boolean items) {
        return source + (items ? ":items" : ":empty");
    }

    private static Counter counter(MeterRegistry registry, String source, String outcome) {
        return Counter.builder(METER)
                .description("TOPómetro pages served by ranking source and bounded outcome")
                .tag("source", source)
                .tag("outcome", outcome)
                .register(registry);
    }
}
