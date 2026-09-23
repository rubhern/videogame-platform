package com.videogameplatform.platform.observability;

import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Confirms that the runtime principal can query the migrated catalogue schema. */
@Component
final class CatalogueStoreHealthIndicator implements HealthIndicator {

    private static final String SCHEMA_PROBE =
            "SELECT EXISTS (SELECT 1 FROM catalogue.catalogue_publication LIMIT 1)";

    private final JdbcTemplate jdbcTemplate;

    CatalogueStoreHealthIndicator(
            DataSource dataSource, ReadinessHealthProperties readinessProperties) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(readinessProperties.catalogueStoreQueryTimeoutSeconds());
    }

    int queryTimeoutSeconds() {
        return jdbcTemplate.getQueryTimeout();
    }

    @Override
    public Health health() {
        try {
            jdbcTemplate.queryForObject(SCHEMA_PROBE, Boolean.class);
            return Health.up().build();
        } catch (DataAccessException exception) {
            return Health.down().build();
        }
    }
}
