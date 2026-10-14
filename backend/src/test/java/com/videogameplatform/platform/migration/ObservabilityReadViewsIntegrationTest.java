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
            statement.execute(
                    "INSERT INTO catalogue.release_external_reference(provider,provider_id,release_id,game_id) "
                            + "SELECT 'IGDB','observability-1',release_id,game_id FROM catalogue.release_snapshot ORDER BY release_id LIMIT 1");
            statement.execute(
                    "INSERT INTO catalogue.release_reconciliation(release_id,provider,last_reconciled_at) "
                            + "SELECT release_id,provider,TIMESTAMPTZ '2026-10-14 02:00:00Z' FROM catalogue.release_external_reference WHERE provider_id='observability-1'");
            // One Game checked with a player score, one checked without one; the rest never
            // checked.
            statement.execute(
                    "INSERT INTO catalogue.game_external_score(game_id,source_name,score_kind,score,score_count,source_url,observed_at,changed_at) "
                            + "SELECT game_id,'IGDB','player',CASE WHEN rank=1 THEN 81.5 END,CASE WHEN rank=1 THEN 12 END,"
                            + "'https://www.igdb.com/games/observability',TIMESTAMPTZ '2026-10-14 02:00:00Z',TIMESTAMPTZ '2026-10-14 02:00:00Z' "
                            + "FROM (SELECT game_id,row_number() OVER (ORDER BY game_id) AS rank FROM catalogue.game_snapshot) g WHERE rank<=2");
            // One retained run report carrying player-score counters.
            statement.execute(
                    "INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,started_at,heartbeat_at,completed_at,run_status,outcome_code,report) "
                            + "VALUES ('70000000-0000-4000-8000-000000000001','IGDB',DATE '2026-10-10',DATE '2026-10-13',"
                            + "TIMESTAMPTZ '2026-10-14 02:00:00Z',TIMESTAMPTZ '2026-10-14 02:00:30Z',TIMESTAMPTZ '2026-10-14 02:01:00Z','succeeded','SYNCHRONIZATION_COMPLETED',"
                            + "jsonb_build_object('counters',jsonb_build_object('playerScoreObservedGames',3,'playerScoreAbsentGames',92,"
                            + "'playerScoreUnavailableGames',0,'playerScoreCreatedGames',3,'playerScoreUpdatedGames',0,"
                            + "'playerScoreRemovedGames',0,'playerScoreUnchangedGames',0)))");
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
                            "SELECT games,releases,player_score_checked_games,player_score_games FROM catalogue.observability_inventory")) {
                result.next();
                assertThat(result.getLong("games")).isEqualTo(12);
                assertThat(result.getLong("releases")).isEqualTo(20);
                // Never checked is not the same as checked without a score.
                assertThat(result.getLong("player_score_checked_games")).isEqualTo(2);
                assertThat(result.getLong("player_score_games")).isEqualTo(1);
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT count(*) AS checked, count(*) FILTER (WHERE has_score) AS scored, "
                                    + "max(score_count) AS max_count, min(observed_at) AS oldest_check "
                                    + "FROM catalogue.observability_player_scores")) {
                result.next();
                assertThat(result.getLong("checked")).isEqualTo(2);
                assertThat(result.getLong("scored")).isEqualTo(1);
                assertThat(result.getLong("max_count")).isEqualTo(12);
                assertThat(result.getTimestamp("oldest_check")).isNotNull();
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT player_score_observed_games, player_score_absent_games, "
                                    + "player_score_created_games, player_score_removed_games "
                                    + "FROM catalogue.observability_runs WHERE player_score_observed_games IS NOT NULL")) {
                result.next();
                assertThat(result.getLong("player_score_observed_games")).isEqualTo(3);
                assertThat(result.getLong("player_score_absent_games")).isEqualTo(92);
                assertThat(result.getLong("player_score_created_games")).isEqualTo(3);
                assertThat(result.getLong("player_score_removed_games")).isZero();
                assertThat(result.next()).isFalse();
            }
            // The last recorded check is distinct from the last published change; none is invented.
            try (var result =
                    statement.executeQuery(
                            "SELECT count(*) AS releases, count(last_reconciled_at) AS checked, "
                                    + "count(*) FILTER (WHERE last_reconciled_at > last_synchronized_at) AS rechecked "
                                    + "FROM catalogue.observability_release_quality")) {
                result.next();
                assertThat(result.getLong("releases")).isEqualTo(20);
                assertThat(result.getLong("checked")).isEqualTo(1);
                assertThat(result.getLong("rechecked")).isEqualTo(1);
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
            assertThatThrownBy(
                            () ->
                                    statement.executeQuery(
                                            "SELECT * FROM catalogue.release_reconciliation"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    statement.executeQuery(
                                            "SELECT * FROM catalogue.game_external_score"))
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
