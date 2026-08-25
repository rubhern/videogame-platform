package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCoverReferenceResolver;
import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.ReleaseBrowsePolicy;
import com.videogameplatform.catalogue.application.internal.ReleaseBrowsePolicy.UnknownUpcomingDatePolicy;
import com.videogameplatform.catalogue.application.internal.ReleaseCatalogueService;
import com.videogameplatform.catalogue.application.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the catalogue application module. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogueReleaseProperties.class)
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
    ProviderCoverReferenceResolver providerCoverReferenceResolver() {
        return new IgdbCoverReferenceResolver();
    }

    @Bean
    BrowseReleasesUseCase browseReleasesUseCase(
            ReleaseBrowseReadPort readPort,
            ProviderCoverReferenceResolver coverResolver,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy,
            CatalogueFreshnessPolicy freshnessPolicy) {
        return new ReleaseCatalogueService(
                readPort, coverResolver, clock, browsePolicy, freshnessPolicy);
    }
}
