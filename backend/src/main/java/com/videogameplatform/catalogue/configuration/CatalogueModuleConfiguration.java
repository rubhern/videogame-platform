package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCoverReferenceResolver;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver;
import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.catalogue.application.details.internal.GameDetailsService;
import com.videogameplatform.catalogue.application.details.port.GameDetailsReadPort;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleaseCalendarUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.internal.FeaturedPeriodPolicy;
import com.videogameplatform.catalogue.application.releases.internal.FeaturedReleaseService;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseBrowsePolicy;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseCalendarService;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseCatalogueService;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort;
import com.videogameplatform.catalogue.application.search.SearchCatalogueUseCase;
import com.videogameplatform.catalogue.application.search.internal.CatalogueSearchPolicy;
import com.videogameplatform.catalogue.application.search.internal.CatalogueSearchService;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the catalogue application module. */
@Configuration(proxyBeanMethods = false)
// Scheduling properties are bound even while scheduling stays off: their policy windows declare the
// forward acquisition horizon that bounds featured months.
@EnableConfigurationProperties({
    CatalogueReleaseProperties.class,
    CatalogueSearchProperties.class,
    CatalogueSchedulingProperties.class
})
class CatalogueModuleConfiguration {
    @Bean
    com.videogameplatform.catalogue.application.details.GetGameListingUseCase gameListingUseCase(
            com.videogameplatform.catalogue.application.details.port.GameListingReadPort read,
            CatalogueCoverPolicy covers) {
        return new com.videogameplatform.catalogue.application.details.internal.GameListingService(
                read, covers);
    }

    @Bean
    com.videogameplatform.catalogue.application.releases.GetExternalPlayerScoresUseCase
            externalPlayerScores(
                    com.videogameplatform.catalogue.application.releases.port
                                    .ExternalPlayerScoresReadPort
                            read) {
        return new com.videogameplatform.catalogue.application.releases.internal
                .ExternalPlayerScoresService(read);
    }

    @Bean
    com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase
            recordCommunityScores(
                    com.videogameplatform.catalogue.application.releases.port.CommunityScoreStore
                            store) {
        return new com.videogameplatform.catalogue.application.releases.internal
                .CommunityScoreService(store);
    }

    @Bean
    ReleaseBrowsePolicy releaseBrowsePolicy(CatalogueReleaseProperties properties) {
        return new ReleaseBrowsePolicy(properties.releaseGroupLimit());
    }

    @Bean
    CatalogueFreshnessPolicy catalogueFreshnessPolicy(CatalogueReleaseProperties properties) {
        return new CatalogueFreshnessPolicy(properties.freshnessThreshold());
    }

    @Bean
    CatalogueSearchPolicy catalogueSearchPolicy(CatalogueSearchProperties properties) {
        return new CatalogueSearchPolicy(properties.releaseContextLimit());
    }

    /** One IGDB resolver applies ADR-0001 to covers and to featured images alike. */
    @Bean
    IgdbCoverReferenceResolver providerCoverReferenceResolver() {
        return new IgdbCoverReferenceResolver();
    }

    @Bean
    CatalogueCoverPolicy catalogueCoverPolicy(ProviderCoverReferenceResolver coverResolver) {
        return new CatalogueCoverPolicy(coverResolver);
    }

    @Bean
    BrowseReleasesUseCase browseReleasesUseCase(
            ReleaseBrowseReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy) {
        return new ReleaseCatalogueService(readPort, coverPolicy, clock, browsePolicy);
    }

    @Bean
    BrowseFeaturedReleasesUseCase browseFeaturedReleasesUseCase(
            FeaturedReleaseReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            ProviderImageReferenceResolver imageResolver,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy,
            CatalogueFreshnessPolicy freshnessPolicy,
            CatalogueSchedulingProperties scheduling) {
        return new FeaturedReleaseService(
                readPort,
                coverPolicy,
                imageResolver,
                clock,
                browsePolicy,
                freshnessPolicy,
                new FeaturedPeriodPolicy(scheduling.forwardAcquisitionDays()));
    }

    /** The calendar reaches the same future as featured months: one forward acquisition bound. */
    @Bean
    BrowseReleaseCalendarUseCase browseReleaseCalendarUseCase(
            ReleaseCalendarReadPort readPort,
            Clock clock,
            CatalogueSchedulingProperties scheduling) {
        return new ReleaseCalendarService(
                readPort, clock, new FeaturedPeriodPolicy(scheduling.forwardAcquisitionDays()));
    }

    @Bean
    SearchCatalogueUseCase searchCatalogueUseCase(
            GameSearchReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            Clock clock,
            CatalogueSearchPolicy searchPolicy) {
        return new CatalogueSearchService(readPort, coverPolicy, clock, searchPolicy);
    }

    @Bean
    com.videogameplatform.catalogue.application.rankings.RankGamesUseCase rankGamesUseCase(
            com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            Clock clock) {
        return new com.videogameplatform.catalogue.application.rankings.internal.GameRankingService(
                readPort, coverPolicy, clock);
    }

    @Bean
    com.videogameplatform.catalogue.application.preferences.ListPlatformsUseCase
            listPlatformsUseCase(
                    com.videogameplatform.catalogue.application.preferences.port
                                    .SelectablePlatformsReadPort
                            read) {
        return new com.videogameplatform.catalogue.application.preferences.internal
                .PlatformListService(read);
    }

    /** One service answers both preferred-platform contracts for the session's own user. */
    @Bean
    com.videogameplatform.catalogue.application.preferences.internal.PlatformPreferencesService
            platformPreferencesService(
                    com.videogameplatform.catalogue.application.preferences.port
                                    .PlatformPreferenceStore
                            store,
                    Clock clock) {
        return new com.videogameplatform.catalogue.application.preferences.internal
                .PlatformPreferencesService(store, clock);
    }

    @Bean
    GetGameDetailsUseCase getGameDetailsUseCase(
            GameDetailsReadPort readPort, CatalogueCoverPolicy coverPolicy, Clock clock) {
        return new GameDetailsService(readPort, coverPolicy, clock);
    }
}
