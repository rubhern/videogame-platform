package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.observability.CatalogueLocalizationMetrics;
import com.videogameplatform.catalogue.adapter.operator.CatalogueLocalizationEndpoint;
import com.videogameplatform.catalogue.adapter.persistence.localization.JdbcLocalizationStore;
import com.videogameplatform.catalogue.adapter.persistence.localization.JdbcTranslationExecutionStore;
import com.videogameplatform.catalogue.adapter.translation.LocalCatalogueTranslationAdapter;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations;
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.localization.internal.CatalogueLocalizationService;
import com.videogameplatform.catalogue.application.localization.internal.CatalogueTranslationAdministration;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogueLocalizationProperties.class)
class CatalogueLocalizationConfiguration {
    @Bean
    LocalizeCatalogueUseCase localizeCatalogueUseCase(
            DataSource dataSource,
            PlatformTransactionManager transactions,
            CatalogueLocalizationProperties properties,
            MeterRegistry registry,
            org.springframework.context.ApplicationEventPublisher events) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(10);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
        return new CatalogueLocalizationService(
                new JdbcLocalizationStore(
                        new NamedParameterJdbcTemplate(jdbc),
                        transaction,
                        term ->
                                events.publishEvent(
                                        new com.videogameplatform.catalogue.application.details
                                                .GenreLabelChanged(term))),
                new LocalCatalogueTranslationAdapter(
                        HttpClient.newBuilder().connectTimeout(properties.timeout()).build(),
                        properties.endpoint(),
                        properties.timeout()),
                new CatalogueLocalizationMetrics(registry));
    }

    @Bean(destroyMethod = "shutdownNow")
    TranslationWorker translationWorker() {
        return new TranslationWorker();
    }

    // A wrapper keeps the catalogue worker out of Spring's general executor selection.
    static final class TranslationWorker {
        private final ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        Thread.ofPlatform().name("catalogue-translation-", 0).daemon().factory());

        public void execute(Runnable task) {
            executor.execute(task);
        }

        public void shutdownNow() {
            executor.shutdownNow();
        }
    }

    @Bean
    CatalogueTranslations catalogueTranslations(
            DataSource dataSource,
            LocalizeCatalogueUseCase localization,
            TranslationWorker worker,
            Clock clock) {
        var commands = new JdbcTemplate(dataSource);
        commands.setQueryTimeout(10);
        return new CatalogueTranslationAdministration(
                new JdbcTranslationExecutionStore(new NamedParameterJdbcTemplate(commands), clock),
                localization,
                worker::execute,
                clock);
    }

    @Bean
    CatalogueLocalizationEndpoint catalogueLocalizationEndpoint(
            LocalizeCatalogueUseCase localization) {
        return new CatalogueLocalizationEndpoint(localization);
    }
}
