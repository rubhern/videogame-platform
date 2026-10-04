package com.videogameplatform.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ObservabilityReadViewsIntegrationTest {
    @Test
    void exposesRealAggregatesWithoutOwnersAndDeniesBaseTableReadsAndWrites() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("observability");
        String reader = "reader_" + database;
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration", "classpath:db/dev-seed")
                .load()
                .migrate();
        try (var connection = PostgreSqlTestDatabase.adminConnection(database);
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ROLE "
                            + reader
                            + " LOGIN PASSWORD 'fixture' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION CONNECTION LIMIT 2");
            String contract =
                    Files.readString(Path.of("../deploy/private-dev/grafana/provision-reader.sql"));
            // Role creation/password transport is exercised by the disposable Compose smoke.
            statement.execute(
                    contract.substring(contract.indexOf("ALTER ROLE videogame_grafana NOSUPERUSER"))
                            .replace("videogame_grafana", reader)
                            .replace("videogame_platform", database)
                            .replace("COMMIT;", ""));
            statement.execute(
                    "INSERT INTO ratings.rating(user_id,game_id,value) VALUES "
                            + "('60000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',10),"
                            + "('60000000-0000-4000-8000-000000000002','30000000-0000-4000-8000-000000000001',8),"
                            + "('60000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000002',4)");
        }
        try (var connection =
                        DriverManager.getConnection(
                                PostgreSqlTestDatabase.adminUrl(database), reader, "fixture");
                var statement = connection.createStatement()) {
            try (var result =
                    statement.executeQuery("SELECT * FROM ratings.observability_summary")) {
                result.next();
                assertThat(result.getLong("active_ratings")).isEqualTo(3);
                assertThat(result.getLong("users_with_ratings")).isEqualTo(2);
                assertThat(result.getLong("rated_games")).isEqualTo(2);
                assertThat(result.getDouble("ratings_per_rating_user")).isEqualTo(1.5);
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT votes,mean_score FROM ratings.observability_game_rankings ORDER BY votes DESC, ordering_key LIMIT 1")) {
                result.next();
                assertThat(result.getLong("votes")).isEqualTo(2);
                assertThat(result.getDouble("mean_score")).isEqualTo(9);
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT games,releases FROM catalogue.observability_inventory")) {
                result.next();
                assertThat(result.getLong("games")).isEqualTo(12);
                assertThat(result.getLong("releases")).isEqualTo(20);
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT connections FROM public.observability_database")) {
                assertThat(result.next()).isTrue();
            }
            assertThatThrownBy(() -> statement.executeQuery("SELECT user_id FROM ratings.rating"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () -> statement.executeQuery("SELECT * FROM catalogue.game_snapshot"))
                    .isInstanceOf(SQLException.class);
            // Privileges, not a session read-only default, are the enforcement boundary.
            statement.execute("SET default_transaction_read_only = off");
            assertThatThrownBy(() -> statement.execute("DELETE FROM ratings.rating"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "DELETE FROM catalogue.observability_release_quality"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () -> statement.execute("CREATE TABLE public.reader_write(id integer)"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
