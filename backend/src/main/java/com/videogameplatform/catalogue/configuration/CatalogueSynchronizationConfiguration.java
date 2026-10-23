package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationLog;
import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.observability.HistoricalSynchronizationProgress;
import com.videogameplatform.catalogue.adapter.operator.CatalogueCoverageEndpoint;
import com.videogameplatform.catalogue.adapter.operator.CatalogueSynchronizationEndpoint;
import com.videogameplatform.catalogue.adapter.persistence.synchronization.JdbcCatalogueSynchronizationStore;
import com.videogameplatform.catalogue.adapter.persistence.synchronization.JdbcHistoricalBackfillStore;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbApiClient;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbApiSettings;
import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbCatalogueProviderAdapter;
import com.videogameplatform.catalogue.adapter.scheduling.BackgroundSynchronizationExecutor;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverageQuery;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatusQuery;
import com.videogameplatform.catalogue.application.administration.ControlCatalogueSynchronization;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization;
import com.videogameplatform.catalogue.application.administration.RequestCatalogueSynchronization;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.HistoricalBackfillService;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationControlService;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationCoverageService;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationRequestService;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationStatusService;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.HistoricalBackfillStore;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    /** Disabling a periodic policy cancels only its deferred slot, never historical work. */
    @Bean
    org.springframework.boot.ApplicationRunner cancelDisabledScheduledPolicies(
            com.videogameplatform.catalogue.application.synchronization.port
                            .ScheduledSynchronizationStore
                    pending,
            @org.springframework.beans.factory.annotation.Value(
                            "${catalogue.synchronization.scheduling.enabled:false}")
                    boolean enabled,
            @org.springframework.beans.factory.annotation.Value(
                            "${catalogue.synchronization.scheduling.near-term.cron:-}")
                    String nearCron,
            @org.springframework.beans.factory.annotation.Value(
                            "${catalogue.synchronization.scheduling.upcoming.cron:-}")
                    String upcomingCron) {
        return args -> {
            if (!enabled || "-".equals(nearCron))
                pending.cancel(
                        com.videogameplatform.catalogue.application.synchronization.port
                                .ScheduledSynchronizationStore.Policy.NEAR_TERM);
            if (!enabled || "-".equals(upcomingCron))
                pending.cancel(
                        com.videogameplatform.catalogue.application.synchronization.port
                                .ScheduledSynchronizationStore.Policy.UPCOMING);
        };
    }

    @Bean
    JdbcHistoricalBackfillStore historicalBackfillStore(DataSource dataSource) {
        return new JdbcHistoricalBackfillStore(
                dataSource, IgdbCatalogueProviderAdapter.PROVIDER_NAME);
    }

    /** One in-process orchestration thread, excluded across instances by PostgreSQL. */
    @Bean(destroyMethod = "close")
    HistoricalExecutor historicalExecutor() {
        return new HistoricalExecutor();
    }

    static final class HistoricalExecutor implements AutoCloseable {
        private final java.util.concurrent.ExecutorService delegate =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(1),
                        Thread.ofPlatform().name("catalogue-historical-", 0).daemon().factory());

        public void execute(Runnable work) {
            delegate.execute(work);
        }

        @Override
        public void close() {
            for (Runnable pending : delegate.shutdownNow()) {
                if (pending instanceof java.util.concurrent.Future<?> future) future.cancel(false);
            }
        }
    }

    @Bean
    HistoricalSynchronization historicalSynchronization(
            HistoricalBackfillStore store,
            CatalogueSynchronizationStore runs,
            SynchronizeCatalogueUseCase synchronization,
            HistoricalExecutor executor,
            Clock clock,
            CatalogueSynchronizationMetrics metrics) {
        return new HistoricalBackfillService(
                store, runs, synchronization, executor::execute, clock, metrics::recordRun);
    }

    @Bean
    CatalogueSynchronizationMetrics catalogueSynchronizationMetrics(MeterRegistry registry) {
        return new CatalogueSynchronizationMetrics(registry);
    }

    @Bean
    SynchronizationProgress catalogueSynchronizationLog(
            HistoricalBackfillStore store, Clock clock) {
        return new HistoricalSynchronizationProgress(
                new CatalogueSynchronizationLog(), store, clock);
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
                properties.maxReleasesPerGame());
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
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            org.springframework.context.ApplicationEventPublisher events) {
        TransactionTemplate writeTransaction = new TransactionTemplate(transactionManager);
        writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writeTransaction.setTimeout(WRITE_TRANSACTION_TIMEOUT_SECONDS);
        return new JdbcCatalogueSynchronizationStore(
                new NamedParameterJdbcTemplate(new JdbcTemplate(dataSource)),
                writeTransaction,
                IgdbCatalogueProviderAdapter.PROVIDER_NAME,
                gameId ->
                        events.publishEvent(
                                new com.videogameplatform.catalogue.application.details
                                        .GameListingChanged(gameId)),
                term ->
                        events.publishEvent(
                                new com.videogameplatform.catalogue.application.details
                                        .GenreLabelChanged(term)));
    }

    @Bean
    SynchronizeCatalogueUseCase synchronizeCatalogueUseCase(
            CatalogueSynchronizationStore store,
            CatalogueProviderPort provider,
            Clock clock,
            SynchronizationPolicy policy,
            CoverSelectionPolicy coverPolicy,
            SynchronizationProgress progress,
            com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase
                    localization) {
        return new CatalogueSynchronizationService(
                store, provider, clock, policy, coverPolicy, progress, localization);
    }

    @Bean
    com.videogameplatform.catalogue.application.synchronization.ReleaseStageRepair
            releaseStageRepair(
                    DataSource dataSource,
                    CatalogueProviderPort provider,
                    SynchronizeCatalogueUseCase synchronization) {
        return new com.videogameplatform.catalogue.application.synchronization.ReleaseStageRepair(
                new com.videogameplatform.catalogue.adapter.persistence.synchronization
                        .JdbcReleaseStageRepairStore(
                        new NamedParameterJdbcTemplate(dataSource), provider.providerName()),
                synchronization);
    }

    @Bean
    com.videogameplatform.catalogue.adapter.operator.ReleaseStageRepairEndpoint
            releaseStageRepairEndpoint(
                    com.videogameplatform.catalogue.application.synchronization.ReleaseStageRepair
                            repair) {
        return new com.videogameplatform.catalogue.adapter.operator.ReleaseStageRepairEndpoint(
                repair);
    }

    @Bean
    SynchronizationCoverageQuery synchronizationCoverageQuery(CatalogueSynchronizationStore store) {
        return new SynchronizationCoverageService(store);
    }

    @Bean
    SynchronizationStatusQuery synchronizationStatusQuery(
            CatalogueSynchronizationStore store, Clock clock, SynchronizationPolicy policy) {
        return new SynchronizationStatusService(store, clock, policy);
    }

    @Bean
    CatalogueCoverageEndpoint catalogueCoverageEndpoint(SynchronizationCoverageQuery coverage) {
        return new CatalogueCoverageEndpoint(coverage);
    }

    /**
     * One worker thread for operator-requested runs, deliberately not an {@code Executor} bean so
     * Spring Boot's application task executor stays in place. A run is submitted only while it holds
     * the single database ownership, so the one-slot queue only absorbs the moment between a
     * finished run releasing ownership and its thread returning. Closing the context interrupts the
     * run; a run that cannot finish is recorded as abandoned by the next run start, as for every
     * trigger.
     */
    @Bean
    BackgroundSynchronizationExecutor backgroundSynchronization(
            CatalogueSynchronizationMetrics metrics) {
        return new BackgroundSynchronizationExecutor(
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(1),
                        Thread.ofPlatform().name("catalogue-sync-request-", 0).daemon().factory()),
                metrics);
    }

    @Bean
    RequestCatalogueSynchronization requestCatalogueSynchronization(
            SynchronizeCatalogueUseCase synchronization,
            BackgroundSynchronizationExecutor backgroundSynchronization) {
        return new SynchronizationRequestService(synchronization, backgroundSynchronization);
    }

    @Bean
    ControlCatalogueSynchronization controlCatalogueSynchronization(
            CatalogueSynchronizationStore store, Clock clock, SynchronizationPolicy policy) {
        return new SynchronizationControlService(store, clock, policy);
    }

    @Bean
    CatalogueSynchronizationEndpoint catalogueSynchronizationEndpoint(
            SynchronizeCatalogueUseCase synchronizeCatalogue,
            CatalogueSynchronizationMetrics metrics) {
        return new CatalogueSynchronizationEndpoint(synchronizeCatalogue, metrics);
    }
}
