package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.operator.CatalogueSynchronizationEndpoint;
import com.videogameplatform.catalogue.adapter.persistence.synchronization.JdbcCatalogueSynchronizationStore;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbApiClient;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbApiSettings;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCatalogueProviderAdapter;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Composition root for provider synchronization.
 *
 * <p>It is separate from the catalogue read composition on purpose: synchronization writes, has its
 * own transaction policy and its own bounded provider access, and nothing in a read path may
 * acquire any of it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    CatalogueSynchronizationProperties.class,
    CatalogueReleaseProperties.class
})
class CatalogueSynchronizationConfiguration {

    /** Each write transaction commits one Game aggregate; provider calls happen outside it. */
    private static final int WRITE_TRANSACTION_TIMEOUT_SECONDS = 120;

    @Bean
    CatalogueSynchronizationMetrics catalogueSynchronizationMetrics(MeterRegistry registry) {
        return new CatalogueSynchronizationMetrics(registry);
    }

    @Bean
    SynchronizationPolicy synchronizationPolicy(CatalogueSynchronizationProperties properties) {
        return new SynchronizationPolicy(
                properties.providerPageSize(),
                properties.maxReleasesPerGame(),
                properties.retainedRuns(),
                properties.abandonRunAfter());
    }

    @Bean
    CoverSelectionPolicy coverSelectionPolicy(CatalogueSynchronizationProperties properties) {
        return new CoverSelectionPolicy(
                properties.cover().fallbackAssetPath(), properties.cover().fallbackSourceName());
    }

    @Bean
    IgdbApiSettings igdbApiSettings(CatalogueSynchronizationProperties properties) {
        CatalogueSynchronizationProperties.Provider provider = properties.provider();
        return new IgdbApiSettings(
                provider.clientId(),
                provider.clientSecret(),
                provider.tokenUri(),
                provider.apiBaseUri(),
                provider.requestTimeout(),
                provider.requestsPerSecond(),
                provider.maxRetries(),
                provider.retryBackoff(),
                properties.maxReleasesPerGame(),
                provider.platformCodes(),
                provider.regionCodes(),
                provider.unknownRegionCode());
    }

    @Bean
    CatalogueProviderPort catalogueProviderPort(
            IgdbApiSettings settings, CatalogueSynchronizationMetrics metrics) {
        // The adapter owns this mapper rather than sharing the application one: provider snake_case
        // naming is a transport detail, and the product HTTP mapper must not inherit it.
        ObjectMapper providerMapper =
                JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build();
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(settings.requestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new IgdbCatalogueProviderAdapter(
                new IgdbApiClient(httpClient, providerMapper, settings),
                providerMapper,
                settings,
                metrics);
    }

    @Bean
    CatalogueSynchronizationStore catalogueSynchronizationStore(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        TransactionTemplate writeTransaction = new TransactionTemplate(transactionManager);
        writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writeTransaction.setTimeout(WRITE_TRANSACTION_TIMEOUT_SECONDS);
        return new JdbcCatalogueSynchronizationStore(
                new NamedParameterJdbcTemplate(new JdbcTemplate(dataSource)),
                writeTransaction,
                IgdbCatalogueProviderAdapter.PROVIDER_NAME);
    }

    @Bean
    SynchronizeCatalogueUseCase synchronizeCatalogueUseCase(
            CatalogueSynchronizationStore store,
            CatalogueProviderPort provider,
            Clock clock,
            SynchronizationPolicy policy,
            CoverSelectionPolicy coverPolicy) {
        return new CatalogueSynchronizationService(store, provider, clock, policy, coverPolicy);
    }

    @Bean
    CatalogueSynchronizationEndpoint catalogueSynchronizationEndpoint(
            SynchronizeCatalogueUseCase synchronizeCatalogue,
            CatalogueSynchronizationMetrics metrics) {
        return new CatalogueSynchronizationEndpoint(synchronizeCatalogue, metrics);
    }
}
