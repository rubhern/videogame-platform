package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.persistence.details.JdbcGameDetailVisits;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class GameDetailVisitConfiguration {
    @Bean
    JdbcGameDetailVisits gameDetailVisits(
            DataSource dataSource, CatalogueJdbcProperties properties, Clock clock) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(properties.readTimeoutSeconds());
        return new JdbcGameDetailVisits(new NamedParameterJdbcTemplate(jdbc), clock);
    }

    @Bean
    VisitRetention visitRetention(JdbcGameDetailVisits visits) {
        return new VisitRetention(visits);
    }

    record VisitRetention(JdbcGameDetailVisits visits) {
        @Scheduled(fixedDelay = 60_000)
        public void purgeExpiredVisits() {
            visits.purgeExpiredBatch();
        }
    }
}
