package com.videogameplatform.ratings.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.ratings.application.port.PersonalRatingsReadPort;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in plan evidence: run explicitly with -Dtest=PersonalRatingsScalabilityIT. */
class PersonalRatingsScalabilityIT {
    @Test
    void recordsActualProductionPlansForGrowingCatalogueAndUserCollections() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("rating_plans");
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
                """
                INSERT INTO catalogue.game(game_id,created_at)
                SELECT md5('plan-game-' || n)::uuid,now() FROM generate_series(1,20000) n
                """);
        jdbc.execute(
                """
                INSERT INTO ratings.game_listing(game_id,slug,canonical_title,normalized_title,cover_kind)
                SELECT md5('plan-game-' || n)::uuid,'game-' || n,'Title ' || n,'title ' || n,'unavailable'
                FROM generate_series(1,20000) n
                """);
        jdbc.execute(
                """
                INSERT INTO ratings.game_listing_alias(game_id,normalized_alias)
                SELECT md5('plan-game-' || n)::uuid,'alias ' || n FROM generate_series(1,20000) n WHERE n % 10 = 0
                """);
        String owner = "00000000-0000-4000-8000-000000000001";
        jdbc.execute(
                """
                INSERT INTO ratings.rating(user_id,game_id,value,created_at,updated_at)
                SELECT CASE WHEN n <= 1000 THEN '00000000-0000-4000-8000-000000000001'::uuid
                    ELSE '00000000-0000-4000-8000-000000000002'::uuid END,
                    md5('plan-game-' || n)::uuid,1 + n % 10,now() - interval '1 day',
                    now() - (n % 100) * interval '1 minute'
                FROM generate_series(1,20000) n
                """);
        jdbc.execute("ANALYZE ratings.rating");
        jdbc.execute("ANALYZE ratings.game_listing");
        jdbc.execute("ANALYZE ratings.game_listing_alias");
        var named = new NamedParameterJdbcTemplate(jdbc);
        var tx = new TransactionTemplate(new JdbcTransactionManager(ds));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var adapter = new JdbcPersonalRatingsReadAdapter(named, tx);
        var evidence =
                new StringBuilder(
                        "20,000 games; 1,000 owner ratings; 19,000 other-user ratings; 2,000 aliases.\n");
        for (var sort : PersonalRatingsReadPort.Sort.values()) {
            for (var tokens : List.of(List.<String>of(), List.of("alias"))) {
                var criteria = new PersonalRatingsReadPort.Criteria(tokens, sort, true, 2, 20);
                var page = adapter.read(owner, criteria);
                assertThat(page.items()).hasSize(20);
                assertThat(page.totalItems()).isEqualTo(tokens.isEmpty() ? 1000 : 100);
                for (String sql :
                        List.of(
                                PersonalRatingsSql.count(criteria),
                                PersonalRatingsSql.page(criteria))) {
                    evidence.append("\n").append(sort).append(" ").append(tokens).append("\n");
                    named.queryForList(
                                    "EXPLAIN (ANALYZE, BUFFERS) " + sql,
                                    PersonalRatingsSql.parameters(owner, criteria),
                                    String.class)
                            .forEach(line -> evidence.append(line).append("\n"));
                }
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/personal-ratings-query-plans.txt"), evidence);
    }
}
