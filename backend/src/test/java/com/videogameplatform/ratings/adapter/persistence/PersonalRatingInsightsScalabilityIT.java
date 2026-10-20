package com.videogameplatform.ratings.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in production query plans; no shared-runner latency threshold. */
class PersonalRatingInsightsScalabilityIT {
    @Test
    void boundsReturnedRowsAndRecordsPlansAsUserAndCatalogueGrow() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("insight_plans");
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var ds =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.adminUsername(),
                        PostgreSqlTestDatabase.adminPassword());
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "INSERT INTO catalogue.game(game_id,created_at) SELECT md5('insight-game-'||n)::uuid,now() FROM generate_series(1,20000) n");
        jdbc.execute(
                "INSERT INTO ratings.game_listing(game_id,slug,canonical_title,normalized_title,cover_kind,genres_projected) SELECT game_id,'game-'||game_id,'Game '||game_id,'game '||game_id,'unavailable',true FROM catalogue.game");
        jdbc.execute(
                "INSERT INTO ratings.genre_label(genre_code,display_name) VALUES ('action','Acción'),('adventure','Aventura'),('rpg','Rol')");
        jdbc.execute(
                "INSERT INTO ratings.game_listing_genre(game_id,genre_code,position) SELECT game_id,genre_code,row_number() OVER (PARTITION BY game_id ORDER BY genre_code) FROM ratings.game_listing CROSS JOIN ratings.genre_label");
        jdbc.execute(
                "INSERT INTO ratings.rating(user_id,game_id,value,created_at,updated_at) SELECT md5('other-user-'||n)::uuid,game_id,8,'2026-10-01T10:00:00Z','2026-10-01T10:00:00Z' FROM ratings.game_listing CROSS JOIN generate_series(1,3) n");
        UUID owner = UUID.fromString("00000000-0000-4000-8000-000000000001");
        var named = new NamedParameterJdbcTemplate(jdbc);
        var tx = new TransactionTemplate(new JdbcTransactionManager(ds));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var read = new JdbcPersonalRatingInsightsReadAdapter(named, tx);
        var evidence =
                new StringBuilder("20,000 games; 60,000 other-user ratings; 60,000 genre links.\n");
        for (int userCount : List.of(10, 1000)) {
            jdbc.update("DELETE FROM ratings.rating WHERE user_id=?", owner);
            jdbc.update(
                    "INSERT INTO ratings.rating(user_id,game_id,value,created_at,updated_at) SELECT ?,md5('insight-game-'||n)::uuid,1+n%10,'2026-10-01T10:00:00Z','2026-10-01T10:00:00Z' FROM generate_series(1,?) n",
                    owner, userCount);
            jdbc.execute("ANALYZE ratings.rating");
            jdbc.execute("ANALYZE ratings.game_listing_genre");
            jdbc.execute("ANALYZE ratings.game_listing");
            jdbc.execute("ANALYZE ratings.genre_label");
            var result = read.read(owner.toString(), LocalDate.of(2026, 10, 20));
            assertThat(result.total()).isEqualTo(userCount);
            assertThat(result.mean()).isEqualByComparingTo("5.5");
            assertThat(result.distribution())
                    .hasSize(10)
                    .allSatisfy(count -> assertThat(count).isEqualTo(userCount / 10));
            assertThat(result.mostRatedGenres())
                    .hasSize(3)
                    .allSatisfy(genre -> assertThat(genre.count()).isEqualTo(userCount));
            assertThat(result.activity()).hasSize(12);
            assertThat(result.activity().getLast().count()).isEqualTo(userCount);
            assertThat(result.highest().tiedGames()).isEqualTo(userCount / 10);
            Map<String, Object> params =
                    Map.of(
                            "user",
                            owner,
                            "value",
                            10,
                            "firstMonth",
                            LocalDate.of(2025, 11, 1),
                            "lastMonth",
                            LocalDate.of(2026, 10, 1),
                            "start",
                            OffsetDateTime.parse("2025-10-31T23:00:00Z"),
                            "end",
                            OffsetDateTime.parse("2026-10-31T23:00:00Z"));
            for (var query :
                    Map.of(
                                    "distribution",
                                    PersonalRatingInsightsSql.DISTRIBUTION,
                                    "coverage",
                                    PersonalRatingInsightsSql.COVERAGE,
                                    "mostGenres",
                                    PersonalRatingInsightsSql.genres(false),
                                    "bestGenres",
                                    PersonalRatingInsightsSql.genres(true),
                                    "extreme",
                                    PersonalRatingInsightsSql.EXTREME,
                                    "activity",
                                    PersonalRatingInsightsSql.ACTIVITY)
                            .entrySet()) {
                evidence.append("\n")
                        .append(userCount)
                        .append(" owner ratings / ")
                        .append(query.getKey())
                        .append("\n");
                var plan =
                        named.queryForList(
                                "EXPLAIN (ANALYZE,BUFFERS) " + query.getValue(),
                                params,
                                String.class);
                plan.forEach(line -> evidence.append(line).append("\n"));
                assertThat(plan)
                        .noneMatch(
                                line ->
                                        line.contains("Seq Scan on rating ")
                                                || line.contains("Seq Scan on game_listing ")
                                                || line.contains(
                                                        "Seq Scan on game_listing_genre "));
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/personal-rating-insights-query-plans.txt"), evidence);
    }
}
