package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler.Policy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Defaults and environment overrides live in application.yaml, not in product rules. */
@ConfigurationProperties("catalogue.synchronization.scheduling")
record CatalogueSchedulingProperties(boolean enabled, Policy nearTerm, Policy upcoming) {
    CatalogueSchedulingProperties {
        if (nearTerm == null || upcoming == null) {
            throw new IllegalArgumentException(
                    "Both catalogue scheduling policies must be configured");
        }
    }
}
