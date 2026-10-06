package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "catalogue.synchronization.scheduling.enabled", havingValue = "true")
@EnableConfigurationProperties(CatalogueSchedulingProperties.class)
@EnableScheduling
class CatalogueSchedulingConfiguration {
    @Bean
    ThreadPoolTaskScheduler catalogueTaskScheduler(Clock clock) {
        var scheduler = new ThreadPoolTaskScheduler();
        // Execute on one dedicated thread, never @Async or a synchronization worker pool.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("catalogue-scheduling-");
        scheduler.setClock(clock);
        return scheduler;
    }

    @Bean
    CatalogueSynchronizationScheduler catalogueSynchronizationScheduler(
            SynchronizeCatalogueUseCase synchronization,
            CatalogueSynchronizationMetrics metrics,
            Clock clock,
            ThreadPoolTaskScheduler catalogueTaskScheduler,
            CatalogueSchedulingProperties properties) {
        return new CatalogueSynchronizationScheduler(
                synchronization,
                metrics,
                clock,
                catalogueTaskScheduler,
                properties.nearTerm(),
                properties.upcoming());
    }
}
