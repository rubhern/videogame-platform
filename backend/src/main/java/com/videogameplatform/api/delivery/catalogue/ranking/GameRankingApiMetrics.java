package com.videogameplatform.api.delivery.catalogue.ranking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for TOPómetro: served pages per closed source, scope kind and
 * outcome. Game and platform identifiers, positions and page numbers never become dimensions.
 */
@Component
final class GameRankingApiMetrics {

    private static final String METER = "catalogue.rankings.page.outcome";
    private static final List<String> SOURCES = List.of("igdb_players", "gameometro_community");
    private static final List<String> SCOPES = List.of("global", "platform");

    private final Map<String, Counter> counters;

    GameRankingApiMetrics(MeterRegistry registry) {
        Map<String, Counter> bySourceAndOutcome = new HashMap<>();
        for (String source : SOURCES) {
            for (String scope : SCOPES) {
                bySourceAndOutcome.put(
                        key(source, scope, true), counter(registry, source, scope, "items"));
                bySourceAndOutcome.put(
                        key(source, scope, false), counter(registry, source, scope, "empty"));
            }
        }
        this.counters = Map.copyOf(bySourceAndOutcome);
    }

    void recordPage(String source, boolean platformScoped, int itemCount) {
        Counter counter =
                counters.get(key(source, platformScoped ? "platform" : "global", itemCount > 0));
        if (counter != null) {
            counter.increment();
        }
    }

    private static String key(String source, String scope, boolean items) {
        return source + ":" + scope + (items ? ":items" : ":empty");
    }

    private static Counter counter(
            MeterRegistry registry, String source, String scope, String outcome) {
        return Counter.builder(METER)
                .description("TOPómetro pages served by ranking source, scope and bounded outcome")
                .tag("source", source)
                .tag("scope", scope)
                .tag("outcome", outcome)
                .register(registry);
    }
}
