package com.videogameplatform.catalogue.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCoverReferenceResolver;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings;
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
import com.videogameplatform.catalogue.application.settings.ProductSetting;
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
                    assertThat(
                                    context.getBean(
                                            com.videogameplatform.catalogue.application
                                                    .administration.EditorialAttentionLists.class))
                            .isInstanceOf(
                                    com.videogameplatform.catalogue.application.attention.internal
                                            .EditorialAttentionService.class);
                    assertThat(context.getBean(BrowseReleasesUseCase.class))
                            .isInstanceOf(ReleaseCatalogueService.class);
                    assertThat(context.getBean(ReleaseBrowsePolicy.class))
                            .isEqualTo(new ReleaseBrowsePolicy(12));
                    assertThat(context.getBean(BrowseFeaturedReleasesUseCase.class))
                            .isInstanceOf(FeaturedReleaseService.class);
                    assertThat(
                                    context.getBean(
                                            com.videogameplatform.catalogue.application.releases
                                                    .BrowseUpcomingHighlightsUseCase.class))
                            .isInstanceOf(
                                    com.videogameplatform.catalogue.application.releases.internal
                                            .UpcomingHighlightsService.class);
                    assertThat(
                                    context.getBean(
                                            com.videogameplatform.catalogue.application.releases
                                                    .BrowseReleaseCalendarUseCase.class))
                            .isInstanceOf(
                                    com.videogameplatform.catalogue.application.releases.internal
                                            .ReleaseCalendarService.class);
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
                    assertThat(
                                    context.getBean(
                                            com.videogameplatform.catalogue.application.rankings
                                                    .RankGamesUseCase.class))
                            .isInstanceOf(
                                    com.videogameplatform.catalogue.application.rankings.internal
                                            .GameRankingService.class);
                    assertThat(
                                    context.getBean(
                                            com.videogameplatform.catalogue.application.preferences
                                                    .ListPlatformsUseCase.class))
                            .isNotNull();
                    assertThat(
                                    context.getBean(
                                            com.videogameplatform.catalogue.application.preferences
                                                    .ReplacePlatformPreferencesUseCase.class))
                            .isSameAs(
                                    context.getBean(
                                            com.videogameplatform.catalogue.application.preferences
                                                    .GetPlatformPreferencesUseCase.class));
                    assertThat(context.getBean(SearchCatalogueUseCase.class))
                            .isInstanceOf(CatalogueSearchService.class);
                    assertThat(context.getBean(CatalogueSearchPolicy.class))
                            .isEqualTo(new CatalogueSearchPolicy(2));
                });
    }

    @Test
    void featuredMonthsFollowTheProductSettingWhateverTheAcquisitionHorizon() {
        // 21 August with the default five months forward reaches January 2027, although the
        // 40-day acquisition horizon ends in September: the operator panel warns, it never clips.
        contextRunner
                .withPropertyValues("catalogue.synchronization.scheduling.upcoming.future-days=40")
                .run(
                        context -> {
                            var featured = context.getBean(BrowseFeaturedReleasesUseCase.class);
                            var january = month("2027-01-01");
                            var february = month("2027-02-01");
                            assertThatThrownBy(() -> featured.browse(january))
                                    .isInstanceOf(CatalogueNotReadyException.class);
                            assertThatThrownBy(() -> featured.browse(february))
                                    .isInstanceOf(FeaturedPeriodOutOfRangeException.class);
                            var settings = context.getBean(ManageProductSettings.class);
                            var coverage = settings.overview().coverage();
                            assertThat(coverage.acquisitionForwardDays()).isEqualTo(40);
                            assertThat(coverage.featuredBeyondAcquisition()).isTrue();
                        });
    }

    @Test
    void theSynchronizationWindowsDefaultToTheConfiguredPolicies() {
        contextRunner
                .withPropertyValues(
                        "catalogue.synchronization.scheduling.upcoming.cron=-",
                        "catalogue.synchronization.scheduling.upcoming.future-days=200")
                .run(
                        context -> {
                            var overview = context.getBean(ManageProductSettings.class).overview();
                            var upcoming =
                                    overview.settings().stream()
                                            .filter(
                                                    entry ->
                                                            entry.setting()
                                                                    == ProductSetting
                                                                            .SYNCHRONIZATION_UPCOMING_FUTURE_DAYS)
                                            .findFirst()
                                            .orElseThrow();
                            assertThat(upcoming.value()).isEqualByComparingTo("200");
                            assertThat(upcoming.origin())
                                    .isEqualTo(ManageProductSettings.Origin.DEPLOYMENT);
                            // A disabled policy never counts towards the acquisition horizon.
                            assertThat(overview.coverage().acquisitionForwardDays()).isEqualTo(28);
                        });
    }

    @Test
    void aConfiguredWindowOutsideItsSettingRangeRefusesToStart() {
        contextRunner
                .withPropertyValues("catalogue.synchronization.scheduling.upcoming.future-days=731")
                .run(context -> assertThat(context).hasFailed());
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
        com.videogameplatform.catalogue.application.attention.port.EditorialAttentionStore
                editorialAttentionStore() {
            return org.mockito.Mockito.mock(
                    com.videogameplatform.catalogue.application.attention.port
                            .EditorialAttentionStore.class);
        }

        @Bean
        com.videogameplatform.catalogue.application.settings.port.ProductSettingStore
                productSettingStore() {
            return new com.videogameplatform.catalogue.application.settings.port
                    .ProductSettingStore() {
                @Override
                public Stored read() {
                    return new Stored(0, Map.of());
                }

                @Override
                public java.util.List<Change> recentChanges(int limit) {
                    return java.util.List.of();
                }

                @Override
                public Optional<Long> apply(
                        long expectedRevision,
                        java.util.List<Write> writes,
                        String operatorId,
                        Instant changedAt) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        ReleaseBrowseReadPort releaseBrowseReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        FeaturedReleaseReadPort featuredReleaseReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        com.videogameplatform.catalogue.application.releases.port.UpcomingHighlightsReadPort
                upcomingHighlightsReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort
                releaseCalendarReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort
                gameRankingReadPort() {
            return criteria -> Optional.empty();
        }

        @Bean
        com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort
                platformGamesReadPort() {
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
        com.videogameplatform.catalogue.application.preferences.port.SelectablePlatformsReadPort
                selectablePlatformsReadPort() {
            return limit -> Optional.empty();
        }

        @Bean
        com.videogameplatform.catalogue.application.preferences.port.PlatformPreferenceStore
                platformPreferenceStore() {
            return new com.videogameplatform.catalogue.application.preferences.port
                    .PlatformPreferenceStore() {
                @Override
                public com.videogameplatform.catalogue.application.preferences.PlatformPreferences
                        find(String userId) {
                    return com.videogameplatform.catalogue.application.preferences
                            .PlatformPreferences.notConfigured();
                }

                @Override
                public Optional<
                                com.videogameplatform.catalogue.application.preferences
                                        .PlatformPreferences>
                        replace(
                                String userId,
                                java.util.Set<String> platformIds,
                                java.time.Instant savedAt) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
