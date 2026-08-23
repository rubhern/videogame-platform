package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Configures the local PostgreSQL implementation of catalogue persistence ports. */
@Configuration(proxyBeanMethods = false)
class CataloguePersistenceConfiguration {

    @Bean
    ReleaseBrowseReadPort releaseBrowseReadPort(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcReleaseBrowseReadAdapter(jdbcTemplate, transactionManager);
    }
}
