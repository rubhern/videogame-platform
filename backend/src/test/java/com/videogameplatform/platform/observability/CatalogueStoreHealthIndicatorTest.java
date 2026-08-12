package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.test.PostgreSqlTestDatabase;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CatalogueStoreHealthIndicatorTest {

    private static final String UNMIGRATED_DATABASE = "observability_unmigrated";

    @BeforeAll
    static void createUnmigratedDatabase() throws SQLException {
        PostgreSqlTestDatabase.createDatabase(UNMIGRATED_DATABASE);
    }

    @Test
    void reportsDownWithoutLeakingDatabaseFailureDetails() {
        var dataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(UNMIGRATED_DATABASE),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        var healthIndicator = new CatalogueStoreHealthIndicator(new JdbcTemplate(dataSource));

        var health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }
}
