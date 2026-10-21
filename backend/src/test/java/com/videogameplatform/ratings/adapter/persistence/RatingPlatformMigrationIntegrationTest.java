package com.videogameplatform.ratings.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RatingPlatformMigrationIntegrationTest {
    @Test
    void historicalRatingsBecomeDirectRatingsAndThePreviousShapeStillWrites() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("rating_platforms");
        PostgreSqlTestDatabase.createDatabase(database);
        var configuration =
                Flyway.configure()
                        .dataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.migratorUsername(),
                                PostgreSqlTestDatabase.migratorPassword())
                        .locations("classpath:db/migration");
        configuration.target("20261020.130000").load().migrate();
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.runtimeUrl(database),
                                PostgreSqlTestDatabase.runtimeUsername(),
                                PostgreSqlTestDatabase.runtimePassword()));
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        UUID game = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        admin.update("INSERT INTO catalogue.game(game_id, created_at) VALUES (?, now())", game);
        jdbc.update(
                "INSERT INTO ratings.rating(user_id, game_id, value) VALUES (?, ?, 7), (?, ?, 10)",
                first,
                game,
                second,
                game);
        Map<String, Object> before =
                jdbc.queryForMap(
                        "SELECT value, created_at, updated_at, version_token FROM ratings.rating WHERE user_id = ?",
                        first);

        configuration.target("latest").load().migrate();

        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT value, created_at, updated_at, version_token, platform_score_count,"
                                + " platform_score_sum, contribution FROM ratings.rating WHERE user_id = ?",
                        first);
        assertThat(after).containsAllEntriesOf(before);
        assertThat(((Number) after.get("platform_score_count")).intValue()).isZero();
        assertThat((BigDecimal) after.get("contribution")).isEqualByComparingTo("7");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM ratings.rating_platform", Integer.class))
                .isZero();
        // The previous application's insert and update shapes still produce direct ratings.
        UUID third = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ratings.rating(user_id, game_id, value, created_at, updated_at, version_token)"
                        + " VALUES (?, ?, 4, now(), now(), gen_random_uuid())",
                third,
                game);
        jdbc.update("UPDATE ratings.rating SET value = 5 WHERE user_id = ?", third);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT contribution FROM ratings.rating WHERE user_id = ?",
                                BigDecimal.class,
                                third))
                .isEqualByComparingTo("5");
        // The observability read contracts keep their columns and one vote per rating.
        assertThat(
                        admin.queryForList(
                                "SELECT score::text || ':' || ratings FROM ratings.observability_score_distribution ORDER BY score",
                                String.class))
                .containsExactly("5:1", "7:1", "10:1");
        assertThat(
                        admin.queryForObject(
                                "SELECT mean_score FROM ratings.observability_game_rankings WHERE ordering_key = ?",
                                Double.class,
                                game))
                .isEqualTo(22.0 / 3);
    }
}
