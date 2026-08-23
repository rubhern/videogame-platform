package com.videogameplatform.catalogue.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CataloguePersistenceIntegrationTest {

    private static final String DATABASE_NAME = "catalogue_persistence";
    private static final String CHECKSUM_DATABASE_NAME = "flyway_checksum";
    private static final String PUBLICATION_ID = "00000000-0000-4000-8000-000000000001";
    private static final String GAME_ID = "30000000-0000-4000-8000-000000000001";
    private static final String PLATFORM_ID = "10000000-0000-4000-8000-000000000001";
    private static final String REGION_ID = "20000000-0000-4000-8000-000000000002";

    @BeforeAll
    static void migrateFreshPostgreSql18Database() throws SQLException {
        PostgreSqlTestDatabase.createDatabase(DATABASE_NAME);
        PostgreSqlTestDatabase.createDatabase(CHECKSUM_DATABASE_NAME);

        Flyway flyway =
                configuredFlyway(DATABASE_NAME, "classpath:db/migration", "classpath:db/dev-seed");

        var migrationResult = flyway.migrate();
        flyway.validate();

        assertThat(migrationResult.migrationsExecuted).isEqualTo(6);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void migratesFromZeroOnPostgreSql18AndLoadsDeterministicSeed() throws SQLException {
        try (Connection connection = PostgreSqlTestDatabase.adminConnection(DATABASE_NAME);
                Statement statement = connection.createStatement()) {
            assertThat(singleString(statement, "SHOW server_version")).startsWith("18.");
            assertThat(
                            singleString(
                                    statement,
                                    "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'catalogue'"))
                    .isEqualTo(PostgreSqlTestDatabase.migratorUsername());
            assertThat(
                            singleInt(
                                    statement,
                                    "SELECT count(*) FROM flyway_schema_history WHERE success"))
                    .isEqualTo(6);
            assertThat(singleInt(statement, "SELECT count(*) FROM catalogue.game_snapshot"))
                    .isEqualTo(8);
            assertThat(singleInt(statement, "SELECT count(*) FROM catalogue.release_snapshot"))
                    .isEqualTo(8);
            assertThat(
                            singleInt(
                                    statement,
                                    "SELECT count(DISTINCT date_precision) FROM catalogue.release_snapshot"))
                    .isEqualTo(5);
            assertThat(
                            singleString(
                                    statement,
                                    "SELECT string_agg(game_id::text, ',' ORDER BY game_id) FROM catalogue.game_snapshot"))
                    .isEqualTo(
                            "30000000-0000-4000-8000-000000000001,"
                                    + "30000000-0000-4000-8000-000000000002,"
                                    + "30000000-0000-4000-8000-000000000003,"
                                    + "30000000-0000-4000-8000-000000000004,"
                                    + "30000000-0000-4000-8000-000000000005,"
                                    + "30000000-0000-4000-8000-000000000006,"
                                    + "30000000-0000-4000-8000-000000000007,"
                                    + "30000000-0000-4000-8000-000000000008");
        }
    }

    @Test
    void rejectsIncoherentReleaseDateRepresentations() throws SQLException {
        List<String> invalidValues =
                List.of(
                        "'day', DATE '2027-06-26', 2027, NULL, NULL",
                        "'month', NULL, 2027, 13, NULL",
                        "'unknown', DATE '2027-06-26', NULL, NULL, NULL");

        for (int index = 0; index < invalidValues.size(); index++) {
            String releaseId = "50000000-0000-4000-8000-00000000000" + (index + 1);
            insertReleaseIdentity(releaseId);
            String sql = releaseSnapshotInsert(releaseId, invalidValues.get(index), PLATFORM_ID);

            assertSqlStateIn(sql, "23514", "22008");
        }
    }

    @Test
    void rejectsExactDatesOutsideTheFourDigitContractYearRange() throws SQLException {
        String releaseId = "50000000-0000-4000-8000-000000000009";
        insertReleaseIdentity(releaseId);

        assertSqlState(
                releaseSnapshotInsert(
                        releaseId, "'day', DATE '10000-01-01', NULL, NULL, NULL", PLATFORM_ID),
                "23514");
    }

    @Test
    void enforcesIdentifiersUniquenessAndForeignKeys() throws SQLException {
        assertSqlState(
                "INSERT INTO catalogue.game (game_id, created_at) VALUES ('"
                        + GAME_ID
                        + "', now())",
                "23505");

        assertSqlState(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) "
                        + "VALUES ('60000000-0000-4000-8000-000000000001', 'another-current', now(), now(), 'product_curated', 'constraint test', true)",
                "23505");

        String releaseId = "50000000-0000-4000-8000-000000000004";
        insertReleaseIdentity(releaseId);
        assertSqlState(
                releaseSnapshotInsert(
                        releaseId,
                        "'day', DATE '2027-06-26', NULL, NULL, NULL",
                        "70000000-0000-4000-8000-000000000001"),
                "23503");
    }

    @Test
    void rejectsUnsafeExternalReferenceUrls() {
        assertSqlState(
                "INSERT INTO catalogue.game_external_reference (game_id, provider, provider_entity_type, provider_id, provider_url) "
                        + "VALUES ('"
                        + GAME_ID
                        + "', 'IGDB', 'game', 'unsafe-example', 'http://www.igdb.com/games/unsafe')",
                "23514");
    }

    @Test
    void preventsExternalReferenceJoinCardinalityFromMultiplyingARelease() throws SQLException {
        execute(
                "INSERT INTO catalogue.game_external_reference (game_id, provider, provider_entity_type, provider_id, provider_url) "
                        + "VALUES ('"
                        + GAME_ID
                        + "', 'IGDB', 'game', 'first-reference', 'https://www.igdb.com/games/example')");
        try {
            assertSqlState(
                    "INSERT INTO catalogue.game_external_reference (game_id, provider, provider_entity_type, provider_id, provider_url) "
                            + "VALUES ('"
                            + GAME_ID
                            + "', 'IGDB', 'game', 'second-reference', 'https://www.igdb.com/games/example-2')",
                    "23505");
        } finally {
            execute(
                    "DELETE FROM catalogue.game_external_reference WHERE game_id = '"
                            + GAME_ID
                            + "' AND provider = 'IGDB' AND provider_entity_type = 'game'");
        }
    }

    @Test
    void rejectsUnsafePublishedCoverDeliveryState() {
        assertSqlState(
                "UPDATE catalogue.game_snapshot SET cover_reference = '/untrusted/cover.svg' "
                        + "WHERE publication_id = '"
                        + PUBLICATION_ID
                        + "' AND game_id = '"
                        + GAME_ID
                        + "'",
                "23514");
        assertSqlState(
                "UPDATE catalogue.game_snapshot SET cover_usage_mode = 'provider_cdn_reference', "
                        + "cover_source = 'IGDB', cover_reference = 'co_safe', "
                        + "cover_source_url = 'https://evil.example/games/example' "
                        + "WHERE publication_id = '"
                        + PUBLICATION_ID
                        + "' AND game_id = '"
                        + GAME_ID
                        + "'",
                "23514");
    }

    @Test
    void grantsRuntimeDmlButNotSchemaChanges() throws SQLException {
        try (Connection connection = PostgreSqlTestDatabase.runtimeConnection(DATABASE_NAME);
                Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, "SELECT count(*) FROM catalogue.game_snapshot"))
                    .isEqualTo(8);
            assertThat(
                            singleInt(
                                    statement,
                                    "SELECT count(*) FROM catalogue.game_external_reference"))
                    .isZero();
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "CREATE TABLE catalogue.forbidden (id integer)"))
                    .isInstanceOf(SQLException.class)
                    .extracting(exception -> ((SQLException) exception).getSQLState())
                    .isEqualTo("42501");
        }
    }

    @Test
    void flywayValidationDetectsChangedAppliedMigration(@TempDir Path temporaryDirectory)
            throws IOException {
        Path migration = temporaryDirectory.resolve("V1__checksum_probe.sql");
        Files.writeString(migration, "CREATE TABLE checksum_probe (id integer PRIMARY KEY);\n");

        Flyway flyway =
                configuredFlyway(CHECKSUM_DATABASE_NAME, "filesystem:" + temporaryDirectory);
        flyway.migrate();
        Files.writeString(
                migration,
                "CREATE TABLE checksum_probe (id integer PRIMARY KEY, changed boolean);\n");

        assertThatThrownBy(flyway::validate).isInstanceOf(FlywayException.class);
    }

    private static Flyway configuredFlyway(String databaseName, String... locations) {
        return Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(databaseName),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations(locations)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .load();
    }

    private static void insertReleaseIdentity(String releaseId) throws SQLException {
        execute(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES ('"
                        + releaseId
                        + "', '"
                        + GAME_ID
                        + "', now())");
    }

    private static String releaseSnapshotInsert(
            String releaseId, String dateValues, String platformId) {
        return "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_quarter, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES ('"
                + PUBLICATION_ID
                + "', '"
                + releaseId
                + "', '"
                + GAME_ID
                + "', '"
                + platformId
                + "', '"
                + REGION_ID
                + "', "
                + dateValues
                + ", 'scheduled', 'product_curated', 'constraint test', 'test_release', now(), 'verified', 'not_required')";
    }

    private static void assertSqlState(String sql, String expectedSqlState) {
        assertThatThrownBy(() -> execute(sql))
                .isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo(expectedSqlState);
    }

    private static void assertSqlStateIn(String sql, String... expectedSqlStates) {
        assertThatThrownBy(() -> execute(sql))
                .isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isIn((Object[]) expectedSqlStates);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = PostgreSqlTestDatabase.adminConnection(DATABASE_NAME);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int singleInt(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
