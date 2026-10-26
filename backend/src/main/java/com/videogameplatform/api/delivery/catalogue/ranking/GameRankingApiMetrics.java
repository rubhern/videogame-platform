package com.videogameplatform.api.delivery.catalogue.ranking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for TOPómetro: served pages per closed source and outcome. Game
 * identifiers, positions and page numbers never become dimensions.
 */
@Component
final class GameRankingApiMetrics {

    private static final String METER = "catalogue.rankings.page.outcome";

    private final Map<String, Counter> counters;

    GameRankingApiMetrics(MeterRegistry registry) {
        this.counters =
                Map.of(
                        key("igdb_players", true), counter(registry, "igdb_players", "items"),
                        key("igdb_players", false), counter(registry, "igdb_players", "empty"),
                        key("gameometro_community", true),
                                counter(registry, "gameometro_community", "items"),
                        key("gameometro_community", false),
                                counter(registry, "gameometro_community", "empty"));
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
