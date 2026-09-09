package com.videogameplatform.catalogue.adapter.observability;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Only closed operation, outcome and mapping vocabularies are metric dimensions. */
public final class CatalogueSynchronizationMetrics {
    public static final String OPERATION_WINDOW = "window";
    public static final String OPERATION_WORKS = "works";
    public static final String OPERATION_RELEASE_DATES = "release_dates";
    private final MeterRegistry registry;

    public CatalogueSynchronizationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRun(CatalogueSynchronizationReport report) {
        String outcome = report.outcome().name().toLowerCase(Locale.ROOT);
        registry.counter("catalogue.synchronization.run", "outcome", outcome).increment();
        registry.timer("catalogue.synchronization.run.duration", "outcome", outcome)
                .record(Duration.between(report.startedAt(), report.completedAt()));
        var c = report.counters();
        record("inspected_release_dates", c.inspectedReleaseDates());
        record("created_games", c.createdGames());
        record("updated_games", c.updatedGames());
        record("unchanged_games", c.unchangedGames());
        record("created_releases", c.createdReleases());
        record("updated_releases", c.updatedReleases());
        record("unchanged_releases", c.unchangedReleases());
        record("deferred_games", c.deferredGames());
        record("failed_games", c.failedGames());
    }

    private void record(String kind, long count) {
        registry.summary("catalogue.synchronization.run.records", "kind", kind).record(count);
    }

    public void recordProviderRequest(
            String operation,
            String outcome,
            long startedNanos,
            ProviderCallStatistics statistics) {
        registry.counter(
                        "catalogue.synchronization.provider.request",
                        "operation",
                        operation,
                        "outcome",
                        outcome.toLowerCase(Locale.ROOT))
                .increment();
        registry.timer(
                        "catalogue.synchronization.provider.request.duration",
                        "operation",
                        operation)
                .record(Duration.ofNanos(System.nanoTime() - startedNanos));
        registry.counter("catalogue.synchronization.provider.retry", "operation", operation)
                .increment(statistics.retries());
    }

    public void recordMappingFailures(List<ProviderMappingFailure> failures) {
        for (var failure : failures) {
            registry.counter(
                            "catalogue.synchronization.provider.mapping.failure",
                            "reason",
                            failure.name().toLowerCase(Locale.ROOT))
                    .increment();
        }
    }
}
