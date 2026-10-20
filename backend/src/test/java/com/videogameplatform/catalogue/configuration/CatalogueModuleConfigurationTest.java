package com.videogameplatform.catalogue.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCoverReferenceResolver;
import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler.Policy;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.details.port.GameDetailsReadPort;
import com.videogameplatform.catalogue.application.details.port.GameListingReadPort;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.FeaturedPeriodOutOfRangeException;
import com.videogameplatform.catalogue.application.releases.GetExternalPlayerScoresUseCase;
import com.videogameplatform.catalogue.application.releases.internal.ExternalPlayerScoresService;
import com.videogameplatform.catalogue.application.releases.internal.FeaturedReleaseService;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseBrowsePolicy;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseCatalogueService;
import com.videogameplatform.catalogue.application.releases.port.ExternalPlayerScoresReadPort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.search.SearchCatalogueUseCase;
import com.videogameplatform.catalogue.application.search.internal.CatalogueSearchPolicy;
import com.videogameplatform.catalogue.application.search.internal.CatalogueSearchService;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
                            "catalogue.releases.release-group-limit=12",
                            "catalogue.releases.freshness-threshold=P14D",
                            "catalogue.search.release-context-limit=2",
                            "catalogue.synchronization.scheduling.near-term.cron=0 0 4 * * *",
                            "catalogue.synchronization.scheduling.near-term.past-days=28",
                            "catalogue.synchronization.scheduling.near-term.future-days=28",
                            "catalogue.synchronization.scheduling.upcoming.cron=0 0 5 * * SUN",
                            "catalogue.synchronization.scheduling.upcoming.past-days=0",
                            "catalogue.synchronization.scheduling.upcoming.future-days=180");

    @Test
    void registersTheCatalogueUseCaseAndPoliciesFromTypedRuntimeConfiguration() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(BrowseReleasesUseCase.class);
                    assertThat(context.getBean(BrowseReleasesUseCase.class))
                            .isInstanceOf(ReleaseCatalogueService.class);
                    assertThat(context.getBean(ReleaseBrowsePolicy.class))
                            .isEqualTo(new ReleaseBrowsePolicy(12));
                    assertThat(context.getBean(BrowseFeaturedReleasesUseCase.class))
                            .isInstanceOf(FeaturedReleaseService.class);
                    assertThat(context).hasSingleBean(GetExternalPlayerScoresUseCase.class);
                    assertThat(context.getBean(GetExternalPlayerScoresUseCase.class))
                            .isInstanceOf(ExternalPlayerScoresService.class);
                    assertThat(context.getBean(CatalogueFreshnessPolicy.class))
                            .isEqualTo(new CatalogueFreshnessPolicy(Duration.ofDays(14)));
                    assertThat(context).hasSingleBean(ProviderCoverReferenceResolver.class);
                    assertThat(context.getBean(ProviderCoverReferenceResolver.class))
                            .isInstanceOf(IgdbCoverReferenceResolver.class);
                    assertThat(context).hasSingleBean(CatalogueCoverPolicy.class);
                    assertThat(context).hasSingleBean(SearchCatalogueUseCase.class);
                    assertThat(context.getBean(SearchCatalogueUseCase.class))
                            .isInstanceOf(CatalogueSearchService.class);
                    assertThat(context.getBean(CatalogueSearchPolicy.class))
                            .isEqualTo(new CatalogueSearchPolicy(2));
                });
    }

    @Test
    void boundsFeaturedMonthsByTheConfiguredForwardAcquisitionHorizon() {
        // 21 August plus 40 days is 30 September: September is the last month wholly inside it.
        contextRunner
                .withPropertyValues("catalogue.synchronization.scheduling.upcoming.future-days=40")
                .run(
                        context -> {
                            var featured = context.getBean(BrowseFeaturedReleasesUseCase.class);
                            // Inside the bounds the read proceeds and finds no publication.
                            var september = month("2026-09-01");
                            var october = month("2026-10-01");
                            assertThatThrownBy(() -> featured.browse(september))
                                    .isInstanceOf(CatalogueNotReadyException.class);
                            assertThatThrownBy(() -> featured.browse(october))
                                    .isInstanceOf(FeaturedPeriodOutOfRangeException.class);
                        });
        // A disabled upcoming policy leaves the daily 28 days: 18 September, so August is last.
        contextRunner
                .withPropertyValues("catalogue.synchronization.scheduling.upcoming.cron=-")
                .run(
                        context -> {
                            var featured = context.getBean(BrowseFeaturedReleasesUseCase.class);
                            var september = month("2026-09-01");
                            assertThatThrownBy(() -> featured.browse(september))
                                    .isInstanceOf(FeaturedPeriodOutOfRangeException.class);
                        });
    }

    @Test
    void theForwardAcquisitionHorizonIsTheWidestPolicyNotDisabledWhateverTheSwitch() {
        var nearTerm = new Policy("0 0 4 * * *", 28, 28);
        var upcoming = new Policy("0 0 5 * * SUN", 0, 180);
        assertThat(
                        new CatalogueSchedulingProperties(false, nearTerm, upcoming)
                                .forwardAcquisitionDays())
                .isEqualTo(180);
        assertThat(
                        new CatalogueSchedulingProperties(true, nearTerm, new Policy("-", 0, 180))
                                .forwardAcquisitionDays())
                .isEqualTo(28);
        assertThat(
                        new CatalogueSchedulingProperties(
                                        true, new Policy("-", 28, 28), new Policy("-", 0, 180))
                                .forwardAcquisitionDays())
                .isZero();
    }

    private static BrowseFeaturedReleasesUseCase.Query month(String first) {
        return new BrowseFeaturedReleasesUseCase.Query(
                BrowseFeaturedReleasesUseCase.Granularity.MONTH,
                Optional.of(java.time.LocalDate.parse(first)));
    }

    @Test
    void rejectsUnsafeSearchBoundsBeforeStartupCompletes() {
        contextRunner
                .withPropertyValues("catalogue.search.release-context-limit=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnsafeFreshnessBoundsBeforeStartupCompletes() {
        assertThatThrownBy(() -> new CatalogueReleaseProperties(25, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogueReleaseProperties(25, Duration.ofDays(366)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidBoundConfigurationBeforeStartupCompletes() {
        contextRunner
                .withPropertyValues("catalogue.releases.release-group-limit=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        ReleaseBrowseReadPort releaseBrowseReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        FeaturedReleaseReadPort featuredReleaseReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        ExternalPlayerScoresReadPort externalPlayerScoresReadPort() {
            return gameIds -> Map.of();
        }

        @Bean
        com.videogameplatform.catalogue.application.releases.port.CommunityScoreStore
                communityScoreStore() {
            return new com.videogameplatform.catalogue.application.releases.port
                    .CommunityScoreStore() {
                @Override
                public void replace(
                        java.util.UUID gameId,
                        com.videogameplatform.catalogue.application.releases
                                        .RecordCommunityScoresUseCase.CommunityScore
                                score) {}

                @Override
                public java.util.List<java.util.UUID> unprojected(
                        java.util.List<java.util.UUID> gameIds) {
                    return java.util.List.of();
                }
            };
        }

        @Bean
        GameSearchReadPort gameSearchReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        GameDetailsReadPort gameDetailsReadPort() {
            return id -> Optional.empty();
        }

        @Bean
        GameListingReadPort gameListingReadPort() {
            return id -> Optional.empty();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
