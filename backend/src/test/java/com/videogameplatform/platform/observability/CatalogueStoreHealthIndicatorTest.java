package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CatalogueStoreHealthIndicatorTest {

    private static final String UNMIGRATED_DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("observability_unmigrated");

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
        var healthIndicator =
                new CatalogueStoreHealthIndicator(
                        dataSource, new ReadinessHealthProperties(Duration.ofSeconds(2)));

        var health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void appliesTheDedicatedBoundedStatementTimeout() {
        var dataSource = new DriverManagerDataSource();
        var healthIndicator =
                new CatalogueStoreHealthIndicator(
                        dataSource, new ReadinessHealthProperties(Duration.ofSeconds(2)));

        assertThat(healthIndicator.queryTimeoutSeconds()).isEqualTo(2);
    }

    @Test
    void rejectsUnboundedOrSubsecondProbeTimeouts() {
        assertThatThrownBy(
                        () -> new ReadinessHealthProperties(Duration.ofSeconds(10).plusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadinessHealthProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
