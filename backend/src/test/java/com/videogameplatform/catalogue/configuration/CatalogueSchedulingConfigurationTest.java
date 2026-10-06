package com.videogameplatform.catalogue.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class CatalogueSchedulingConfigurationTest {
    private final SynchronizeCatalogueUseCase synchronization =
            mock(SynchronizeCatalogueUseCase.class);
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneId.of("Europe/Madrid"));
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(CatalogueSchedulingConfiguration.class)
                    .withBean(SynchronizeCatalogueUseCase.class, () -> synchronization)
                    .withBean(Clock.class, () -> clock)
                    .withBean(
                            CatalogueSynchronizationMetrics.class,
                            () -> new CatalogueSynchronizationMetrics(new SimpleMeterRegistry()));

    @Test
    void executableDefaultsKeepSchedulerAbsentEvenWithProviderCredentials() {
        runner.withPropertyValues(
                        "catalogue.synchronization.provider.client-id=test",
                        "catalogue.synchronization.provider.client-secret=test")
                .run(
                        context -> {
                            assertThat(context)
                                    .doesNotHaveBean(CatalogueSynchronizationScheduler.class);
                            assertThat(context).doesNotHaveBean(ThreadPoolTaskScheduler.class);
                            verifyNoInteractions(synchronization);
                        });
    }

    @Test
    void optInWiresOneDedicatedThreadAndTheApplicationClockWithExecutablePolicyDefaults() {
        runner.withPropertyValues("catalogue.synchronization.scheduling.enabled=true")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasSingleBean(CatalogueSynchronizationScheduler.class);
                            var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
                            assertThat(scheduler.getPoolSize()).isEqualTo(1);
                            assertThat(scheduler.getClock()).isSameAs(clock);
                            assertThat(
                                            context.getBean(
                                                            ScheduledAnnotationBeanPostProcessor
                                                                    .class)
                                                    .getScheduledTasks())
                                    .hasSize(2);
                            var properties = context.getBean(CatalogueSchedulingProperties.class);
                            assertThat(properties.nearTerm().pastDays()).isEqualTo(28);
                            assertThat(properties.nearTerm().futureDays()).isEqualTo(28);
                            assertThat(properties.upcoming().futureDays()).isEqualTo(180);
                            verifyNoInteractions(synchronization);
                        });
    }

    @Test
    void bindsIndependentEnvironmentOverridesIncludingPerPolicyDisable() {
        runner.withPropertyValues(
                        "CATALOGUE_SYNC_SCHEDULING_ENABLED=true",
                        "CATALOGUE_SYNC_NEAR_TERM_CRON=-",
                        "CATALOGUE_SYNC_NEAR_TERM_PAST_DAYS=7",
                        "CATALOGUE_SYNC_UPCOMING_CRON=0 30 6 * * MON",
                        "CATALOGUE_SYNC_UPCOMING_FUTURE_DAYS=90")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasSingleBean(CatalogueSynchronizationScheduler.class);
                            var properties = context.getBean(CatalogueSchedulingProperties.class);
                            assertThat(properties.nearTerm().cron()).isEqualTo("-");
                            assertThat(properties.nearTerm().pastDays()).isEqualTo(7);
                            assertThat(properties.upcoming().cron()).isEqualTo("0 30 6 * * MON");
                            assertThat(properties.upcoming().futureDays()).isEqualTo(90);
                            assertThat(
                                            context.getBean(
                                                            ScheduledAnnotationBeanPostProcessor
                                                                    .class)
                                                    .getScheduledTasks())
                                    .hasSize(1);
                        });
    }
}
