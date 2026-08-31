package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.persistence.JdbcReleaseBrowseReadAdapter;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
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
    ReleaseBrowseExecution catalogueReleaseBrowseExecution(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            CatalogueJdbcProperties properties) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(properties.readTimeoutSeconds());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(properties.readTimeoutSeconds());

        return new ReleaseBrowseExecution(
                new NamedParameterJdbcTemplate(jdbcTemplate), transaction);
    }

    @Bean
    ReleaseBrowseReadPort releaseBrowseReadPort(ReleaseBrowseExecution execution) {
        return new JdbcReleaseBrowseReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    record ReleaseBrowseExecution(
            NamedParameterJdbcTemplate jdbcOperations, TransactionTemplate readTransaction) {}
}
