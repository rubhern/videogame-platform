package com.videogameplatform.catalogue.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.ReleaseBrowsePolicy;
import com.videogameplatform.catalogue.application.internal.ReleaseCatalogueService;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CatalogueModuleConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            CatalogueModuleConfiguration.class, TestDependencies.class)
                    .withPropertyValues(
                            "catalogue.releases.recent-window-months=4",
                            "catalogue.releases.upcoming-window-months=8",
                            "catalogue.releases.freshness-threshold=P14D");

    @Test
    void registersTheCatalogueUseCaseAndPoliciesFromTypedRuntimeConfiguration() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(BrowseReleasesUseCase.class);
                    assertThat(context.getBean(BrowseReleasesUseCase.class))
                            .isInstanceOf(ReleaseCatalogueService.class);
                    assertThat(context.getBean(ReleaseBrowsePolicy.class))
                            .isEqualTo(
                                    new ReleaseBrowsePolicy(
                                            4,
                                            8,
                                            ReleaseBrowsePolicy.UnknownUpcomingDatePolicy
                                                    .INCLUDE_AS_TBA));
                    assertThat(context.getBean(CatalogueFreshnessPolicy.class))
                            .isEqualTo(new CatalogueFreshnessPolicy(Duration.ofDays(14)));
                });
    }

    @Test
    void rejectsUnsafeFreshnessBoundsBeforeStartupCompletes() {
        assertThatThrownBy(() -> new CatalogueReleaseProperties(6, 6, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogueReleaseProperties(6, 6, Duration.ofDays(366)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidBoundConfigurationBeforeStartupCompletes() {
        contextRunner
                .withPropertyValues("catalogue.releases.recent-window-months=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        ReleaseBrowseReadPort releaseBrowseReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
