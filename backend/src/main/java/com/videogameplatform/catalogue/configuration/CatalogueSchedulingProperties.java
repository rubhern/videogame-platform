package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler.Policy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Defaults and environment overrides live in application.yaml, not in product rules. The policy
 * windows are only the defaults of the operator's synchronization window settings (#450); the
 * cron expressions stay deployment configuration.
 */
@ConfigurationProperties("catalogue.synchronization.scheduling")
record CatalogueSchedulingProperties(boolean enabled, Policy nearTerm, Policy upcoming) {
    CatalogueSchedulingProperties {
        if (nearTerm == null || upcoming == null) {
            throw new IllegalArgumentException(
                    "Both catalogue scheduling policies must be configured");
        }
    }

    /** Whether the near-term policy is registered, not disabled with {@code '-'}. */
    boolean nearTermScheduled() {
        return !ScheduledTaskRegistrar.CRON_DISABLED.equals(nearTerm.cron());
    }

    /** Whether the upcoming policy is registered, not disabled with {@code '-'}. */
    boolean upcomingScheduled() {
        return !ScheduledTaskRegistrar.CRON_DISABLED.equals(upcoming.cron());
    }
}
