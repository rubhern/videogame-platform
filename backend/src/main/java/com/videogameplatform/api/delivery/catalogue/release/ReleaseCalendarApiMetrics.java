package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.catalogue.application.releases.ReleaseCalendarResult;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for the release calendar: how many days of a served month hold a
 * release, for the current month or a later one. Its one dimension, {@code month}, is already in
 * the private-dev Prometheus label allowlist; no month value, day, game or filter is a dimension.
 */
@Component
final class ReleaseCalendarApiMetrics {

    static final String DAYS = "catalogue.release.calendar.days";

    private final MeterRegistry registry;

    ReleaseCalendarApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordCalendar(ReleaseCalendarResult result) {
        DistributionSummary.builder(DAYS)
                .description("Days of a served calendar month holding at least one release")
                .tag("month", result.navigation().previous().isEmpty() ? "current" : "later")
                .register(registry)
                .record(result.days().size());
    }
}
