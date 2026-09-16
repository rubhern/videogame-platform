package com.videogameplatform.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class DatabaseMigrationApplicationTest {

    @Test
    void appliesProductionMigrationsWithTheDedicatedActorAndReportsTheCurrentVersion()
            throws SQLException {
        String databaseName = PostgreSqlTestDatabase.isolatedDatabaseName("deployment_migration");
        PostgreSqlTestDatabase.createDatabase(databaseName);
        var outputBytes = new ByteArrayOutputStream();

        String migrationVersion;
        try (var output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8)) {
            migrationVersion =
                    DatabaseMigrationApplication.run(
                            Map.of(
                                    "APPLICATION_MIGRATION_DB_URL",
                                    PostgreSqlTestDatabase.adminUrl(databaseName),
                                    "APPLICATION_MIGRATION_DB_USERNAME",
                                    PostgreSqlTestDatabase.migratorUsername(),
                                    "APPLICATION_MIGRATION_DB_PASSWORD",
                                    PostgreSqlTestDatabase.migratorPassword()),
                            output);
        }

        assertThat(migrationVersion).matches("[0-9]+(?:\\.[0-9]+)*");
        assertThat(outputBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(
                        DatabaseMigrationApplication.MIGRATION_VERSION_PREFIX
                                + migrationVersion
                                + System.lineSeparator());

        try (Connection connection = PostgreSqlTestDatabase.adminConnection(databaseName);
                Statement statement = connection.createStatement()) {
            assertThat(
                            singleInt(
                                    statement,
                                    "SELECT count(*) FROM flyway_schema_history WHERE success"))
                    .isEqualTo(10);
            assertThat(
                            singleString(
                                    statement,
                                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"))
                    .isEqualTo(migrationVersion);
        }

        try (Connection connection = PostgreSqlTestDatabase.runtimeConnection(databaseName);
                Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, "SELECT count(*) FROM catalogue.game")).isZero();
        }
    }

    @Test
    void rejectsMissingMigrationConfigurationBeforeStartingFlyway() {
        try (var output = new PrintStream(new ByteArrayOutputStream())) {
            assertThatThrownBy(() -> DatabaseMigrationApplication.run(Map.of(), output))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "Required migration configuration is missing: APPLICATION_MIGRATION_DB_URL");
        }
    }

    private static int singleInt(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
