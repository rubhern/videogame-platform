package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCoverReferenceResolver;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseBrowsePolicy;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseBrowsePolicy.UnknownUpcomingDatePolicy;
import com.videogameplatform.catalogue.application.releases.internal.ReleaseCatalogueService;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
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
@EnableConfigurationProperties({CatalogueReleaseProperties.class, CatalogueSearchProperties.class})
class CatalogueModuleConfiguration {

    @Bean
    ReleaseBrowsePolicy releaseBrowsePolicy(CatalogueReleaseProperties properties) {
        return new ReleaseBrowsePolicy(
                properties.recentWindowMonths(),
                properties.upcomingWindowMonths(),
                UnknownUpcomingDatePolicy.INCLUDE_AS_TBA);
    }

    @Bean
    CatalogueFreshnessPolicy catalogueFreshnessPolicy(CatalogueReleaseProperties properties) {
        return new CatalogueFreshnessPolicy(properties.freshnessThreshold());
    }

    @Bean
    CatalogueSearchPolicy catalogueSearchPolicy(CatalogueSearchProperties properties) {
        return new CatalogueSearchPolicy(properties.releaseContextLimit());
    }

    @Bean
    ProviderCoverReferenceResolver providerCoverReferenceResolver() {
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
            ReleaseBrowsePolicy browsePolicy,
            CatalogueFreshnessPolicy freshnessPolicy) {
        return new ReleaseCatalogueService(
                readPort, coverPolicy, clock, browsePolicy, freshnessPolicy);
    }

    @Bean
    SearchCatalogueUseCase searchCatalogueUseCase(
            GameSearchReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            Clock clock,
            CatalogueSearchPolicy searchPolicy,
            CatalogueFreshnessPolicy freshnessPolicy) {
        return new CatalogueSearchService(
                readPort, coverPolicy, clock, searchPolicy, freshnessPolicy);
    }
}
