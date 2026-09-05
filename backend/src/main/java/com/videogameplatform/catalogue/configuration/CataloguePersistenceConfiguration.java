package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcReleaseBrowseReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.search.JdbcGameSearchReadAdapter;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Composition root for catalogue persistence adapters and their execution policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogueJdbcProperties.class)
class CataloguePersistenceConfiguration {

    @Bean
    CatalogueReadExecution catalogueReadExecution(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            CatalogueJdbcProperties properties) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(properties.readTimeoutSeconds());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(properties.readTimeoutSeconds());

        return new CatalogueReadExecution(
                new NamedParameterJdbcTemplate(jdbcTemplate), transaction);
    }

    @Bean
    ReleaseBrowseReadPort releaseBrowseReadPort(CatalogueReadExecution execution) {
        return new JdbcReleaseBrowseReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    GameSearchReadPort gameSearchReadPort(CatalogueReadExecution execution) {
        return new JdbcGameSearchReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    /** Shared read-only, bounded-timeout execution policy for every catalogue query. */
    record CatalogueReadExecution(
            NamedParameterJdbcTemplate jdbcOperations, TransactionTemplate readTransaction) {}
}
