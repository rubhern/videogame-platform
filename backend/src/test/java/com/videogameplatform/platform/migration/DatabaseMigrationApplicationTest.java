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
                    .isEqualTo(21);
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
    void forwardCorrectionRemovesVisitsAndPreservesGamesAndSelectedMedia() throws SQLException {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("featured_upgrade");
        PostgreSqlTestDatabase.createDatabase(database);
        org.flywaydb.core.Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .target("20261003.160000")
                .load()
                .migrate();
        try (var connection = PostgreSqlTestDatabase.adminConnection(database);
                var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO catalogue.game VALUES ('30000000-0000-4000-8000-000000000151', now())");
            statement.execute(
                    "INSERT INTO catalogue.game_popularity VALUES ('30000000-0000-4000-8000-000000000151', 0.1, 'IGDB', NULL, now())");
            statement.execute(
                    "INSERT INTO catalogue.game_featured_media VALUES ('30000000-0000-4000-8000-000000000151', 'image', 'artwork', 'arupgrade', 1920, 1080, false, 'IGDB', 'https://www.igdb.com/games/upgrade', now())");
        }
        org.flywaydb.core.Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (var connection = PostgreSqlTestDatabase.runtimeConnection(database);
                var statement = connection.createStatement()) {
            assertThat(singleInt(statement, "SELECT count(*) FROM catalogue.game")).isEqualTo(1);
            assertThat(singleInt(statement, "SELECT count(*) FROM catalogue.game_featured_media"))
                    .isEqualTo(2);
            assertThat(
                            singleString(
                                    statement,
                                    "SELECT image_reference FROM catalogue.game_featured_media WHERE media_role = 'card_image'"))
                    .isEqualTo("arupgrade");
            assertThat(
                            singleInt(
                                    statement,
                                    "SELECT count(*) FROM catalogue.game_featured_evidence"))
                    .isZero();
            assertThat(
                            singleString(
                                    statement,
                                    "SELECT to_regclass('catalogue.game_popularity')::text"))
                    .isNull();
        }
    }

    @Test
    void localizationUpgradeCuratesStableReferencesAndPreservesOriginalsWithoutInference()
            throws SQLException {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("localization_upgrade");
        PostgreSqlTestDatabase.createDatabase(database);
        var configuration =
                org.flywaydb.core.Flyway.configure()
                        .dataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.migratorUsername(),
                                PostgreSqlTestDatabase.migratorPassword())
                        .locations("classpath:db/migration", "classpath:db/dev-seed");
        configuration.target("20261004.120000").load().migrate();
        var jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                PostgreSqlTestDatabase.runtimeUrl(database),
                                PostgreSqlTestDatabase.runtimeUsername(),
                                PostgreSqlTestDatabase.runtimePassword()));
        jdbc.update(
                "INSERT INTO catalogue.genre(genre_id,code,display_name) VALUES ('10000000-0000-0000-0000-000000000001','igdb-12','Role-playing (RPG)'),('10000000-0000-0000-0000-000000000002','igdb-999','Unknown genre')");
        jdbc.update(
                "INSERT INTO catalogue.genre_external_reference(provider,provider_id,genre_id) VALUES ('IGDB','12','10000000-0000-0000-0000-000000000001'),('IGDB','999','10000000-0000-0000-0000-000000000002')");
        jdbc.update(
                "INSERT INTO catalogue.game_mode(game_mode_id,code,display_name) VALUES ('20000000-0000-0000-0000-000000000001','igdb-1','Single player')");
        jdbc.update(
                "INSERT INTO catalogue.game_mode_external_reference(provider,provider_id,game_mode_id) VALUES ('IGDB','1','20000000-0000-0000-0000-000000000001')");
        jdbc.update(
                "UPDATE catalogue.game_snapshot SET summary_kind='sourced',summary_text='Original English summary.',summary_language='en',summary_source_kind='external_provider',summary_source_name='IGDB',summary_source_entity_type='games' WHERE slug='death-stranding-2-on-the-beach'");
        var sources =
                jdbc.queryForList(
                        "SELECT game_id,summary_text,summary_language,summary_source_name FROM catalogue.game_snapshot ORDER BY game_id");
        var identities =
                jdbc.queryForList(
                        "SELECT * FROM catalogue.genre_external_reference ORDER BY provider_id");
        var revision =
                jdbc.queryForObject(
                        "SELECT catalogue_version FROM catalogue.catalogue_publication",
                        String.class);

        configuration.target("latest").load().migrate();

        assertThat(
                        jdbc.queryForList(
                                "SELECT game_id,summary_text,summary_language,summary_source_name FROM catalogue.game_snapshot ORDER BY game_id"))
                .isEqualTo(sources);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM catalogue.genre_external_reference ORDER BY provider_id"))
                .isEqualTo(identities);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT display_name,source_label,label_origin FROM catalogue.genre WHERE code='igdb-12'"))
                .containsEntry("display_name", "Rol (RPG)")
                .containsEntry("source_label", "Role-playing (RPG)")
                .containsEntry("label_origin", "curated");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT display_name,source_label,label_origin FROM catalogue.genre WHERE code='igdb-999'"))
                .containsEntry("display_name", "Unknown genre")
                .containsEntry("source_label", "Unknown genre")
                .containsEntry("label_origin", "source");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT display_name FROM catalogue.game_mode", String.class))
                .isEqualTo("Un jugador");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.content_translation",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.game_summary_translation",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT catalogue_version FROM catalogue.catalogue_publication",
                                String.class))
                .isNotEqualTo(revision);
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
