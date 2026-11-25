package com.videogameplatform.identity.configuration;

import com.videogameplatform.identity.adapter.persistence.JdbcPlayerProfiles;
import com.videogameplatform.identity.application.PlayerProfiles;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class PlayerProfileConfiguration {
    @Bean
    PlayerProfiles playerProfiles(
            NamedParameterJdbcOperations jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        var writes = new TransactionTemplate(transactionManager);
        writes.setTimeout(5);
        return new JdbcPlayerProfiles(jdbc, writes, clock);
    }
}
