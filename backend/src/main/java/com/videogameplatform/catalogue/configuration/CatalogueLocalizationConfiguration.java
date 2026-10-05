package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.observability.CatalogueLocalizationMetrics;
import com.videogameplatform.catalogue.adapter.operator.CatalogueLocalizationEndpoint;
import com.videogameplatform.catalogue.adapter.persistence.localization.JdbcLocalizationStore;
import com.videogameplatform.catalogue.adapter.translation.LocalCatalogueTranslationAdapter;
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.localization.internal.CatalogueLocalizationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
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

    @Bean
    CatalogueLocalizationEndpoint catalogueLocalizationEndpoint(
            LocalizeCatalogueUseCase localization) {
        return new CatalogueLocalizationEndpoint(localization);
    }
}
