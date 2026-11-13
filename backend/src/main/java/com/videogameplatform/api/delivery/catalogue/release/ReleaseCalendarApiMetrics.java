package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.catalogue.application.releases.ReleaseCalendarResult;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for the release calendar: how many days of a served month hold a
 * release, grouped as past, current or later. Its one dimension, {@code month}, is already in
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
        int relation = result.month().compareTo(YearMonth.from(result.evaluatedOn()));
        DistributionSummary.builder(DAYS)
                .description("Days of a served calendar month holding at least one release")
                .tag("month", relation < 0 ? "past" : relation == 0 ? "current" : "later")
                .register(registry)
                .record(result.days().size());
    }
}
