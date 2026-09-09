package com.videogameplatform.catalogue.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.operator.CatalogueSynchronizationEndpoint;
import com.videogameplatform.catalogue.adapter.persistence.synchronization.JdbcCatalogueSynchronizationStore;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbApiSettings;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCatalogueProviderAdapter;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** The synchronization composition root and the bounds its typed configuration enforces. */
class CatalogueSynchronizationConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            CatalogueSynchronizationConfiguration.class, TestDependencies.class)
                    .withPropertyValues(
                            "catalogue.synchronization.provider-page-size=500",
                            "catalogue.releases.recent-window-months=6",
                            "catalogue.releases.upcoming-window-months=6",
                            "catalogue.releases.freshness-threshold=P7D",
                            "catalogue.synchronization.max-releases-per-game=25",
                            "catalogue.synchronization.retained-runs=50",
                            "catalogue.synchronization.abandon-run-after=PT30M",
                            "catalogue.synchronization.cover.fallback-asset-path=/assets/covers/fallback.svg",
                            "catalogue.synchronization.cover.fallback-source-name=VideoGame Platform",
                            "catalogue.synchronization.provider.token-uri=https://id.twitch.tv/oauth2/token",
                            "catalogue.synchronization.provider.api-base-uri=https://api.igdb.com/v4/",
                            "catalogue.synchronization.provider.request-timeout=10s",
                            "catalogue.synchronization.provider.requests-per-second=3",
                            "catalogue.synchronization.provider.max-retries=2",
                            "catalogue.synchronization.provider.retry-backoff=250ms",
                            "catalogue.synchronization.provider.platform-codes.ps5=playstation-5",
                            "catalogue.synchronization.provider.region-codes.europe=europe",
                            "catalogue.synchronization.provider.unknown-region-code=unknown");

    @Test
    void wiresTheOperatorCommandOntoTheBoundedUseCaseAndItsAdapters() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(SynchronizeCatalogueUseCase.class);
                    assertThat(context.getBean(SynchronizeCatalogueUseCase.class))
                            .isInstanceOf(CatalogueSynchronizationService.class);
                    assertThat(context.getBean(CatalogueProviderPort.class))
                            .isInstanceOf(IgdbCatalogueProviderAdapter.class);
                    assertThat(context.getBean(CatalogueSynchronizationStore.class))
                            .isInstanceOf(JdbcCatalogueSynchronizationStore.class);
                    assertThat(context).hasSingleBean(CatalogueSynchronizationEndpoint.class);
                    assertThat(context.getBean(SynchronizationPolicy.class))
                            .isEqualTo(
                                    new SynchronizationPolicy(500, 25, 50, Duration.ofMinutes(30)));
                    assertThat(context).hasSingleBean(CoverSelectionPolicy.class);
                });
    }

    @Test
    void reportsSynchronizationAsDisabledWhileNoCredentialIsConfigured() {
        contextRunner.run(
                context ->
                        assertThat(context.getBean(CatalogueProviderPort.class).isConfigured())
                                .isFalse());
    }

    @Test
    void refusesAProviderRequestRateAboveTheApprovedLocalLimit() {
        contextRunner
                .withPropertyValues("catalogue.synchronization.provider.requests-per-second=10")
                .run(
                        context ->
                                assertThat(context)
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining("requests per second"));
    }

    @Test
    void refusesAnUnboundedRunConfiguration() {
        assertThatThrownBy(() -> new SynchronizationPolicy(0, 25, 50, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid synchronization bounds");
    }

    @Test
    void keepsTheApprovedRateLimitOnTheProviderSettingsThemselves() {
        assertThat(IgdbApiSettings.MAX_REQUESTS_PER_SECOND).isEqualTo(3.0d);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        DataSource dataSource() {
            SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
            dataSource.setDriverClass(org.postgresql.Driver.class);
            dataSource.setUrl("jdbc:postgresql://localhost:5432/unused");
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Clock applicationClock() {
            return Clock.fixed(Instant.parse("2026-09-06T08:00:00Z"), ZoneOffset.UTC);
        }
    }
}
