package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler.Policy;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Defaults and environment overrides live in application.yaml, not in product rules. */
@ConfigurationProperties("catalogue.synchronization.scheduling")
record CatalogueSchedulingProperties(boolean enabled, Policy nearTerm, Policy upcoming) {
    CatalogueSchedulingProperties {
        if (nearTerm == null || upcoming == null) {
            throw new IllegalArgumentException(
                    "Both catalogue scheduling policies must be configured");
        }
    }

    /**
     * How far ahead recurring acquisition is configured to reach: the widest future window among
     * the policies not disabled with {@code '-'}, or zero when both are. This is the configured
     * horizon, independent of the {@code enabled} switch, which only opts a runtime into running
     * the policies; whether a run actually covered a period is acquisition coverage, not this.
     */
    int forwardAcquisitionDays() {
        return Stream.of(nearTerm, upcoming)
                .filter(policy -> !ScheduledTaskRegistrar.CRON_DISABLED.equals(policy.cron()))
                .mapToInt(Policy::futureDays)
                .max()
                .orElse(0);
    }
}
