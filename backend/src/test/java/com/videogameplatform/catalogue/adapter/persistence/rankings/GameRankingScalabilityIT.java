package com.videogameplatform.catalogue.adapter.persistence.rankings;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in local scalability evidence for UC-018; intentionally excluded from the normal test
 * pattern. Run with {@code -Dtest=GameRankingScalabilityIT -Dranking.scale.games=100000}.
 */
class GameRankingScalabilityIT {

    private static final String PUBLICATION_ID = "90000000-0000-4000-8000-000000000386";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void ranksCountsAndPagesInPostgreSqlFromTheRankIndexes() throws Exception {
        int games = Integer.getInteger("ranking.scale.games", 100_000);
        if (games < 10_000 || games > 1_000_000) {
            throw new IllegalArgumentException(
                    "ranking.scale.games must be between 10000 and 1000000");
        }
        String databaseName = PostgreSqlTestDatabase.isolatedDatabaseName("ranking_scale_" + games);
        PostgreSqlTestDatabase.createDatabase(databaseName);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(databaseName),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(databaseName),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        seed(admin, games);

        DataSource runtime =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(databaseName),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        var jdbc = new NamedParameterJdbcTemplate(runtime);
        var adapter = new JdbcGameRankingReadAdapter(jdbc, readTransaction(runtime));

        for (Source source : Source.values()) {
            var first = adapter.findRankedGames(criteria(source, 1)).orElseThrow();
            var deep = adapter.findRankedGames(criteria(source, 200)).orElseThrow();
            // A page of 20 stays 20 games in Java whatever the catalogue holds.
            assertThat(first.items()).hasSize(20);
            assertThat(first.items())
                    .extracting(GameRankingReadPort.Item::gameId)
                    .doesNotHaveDuplicates();
            assertThat(deep.items()).hasSizeLessThanOrEqualTo(20);

            Map<String, Object> parameters =
                    GameRankingSql.parameters(PUBLICATION_ID, criteria(source, 1));
            JsonNode page = explain(jdbc, GameRankingSql.page(source), parameters);
            JsonNode deepPage =
                    explain(
                            jdbc,
                            GameRankingSql.page(source),
                            GameRankingSql.parameters(PUBLICATION_ID, criteria(source, 200)));
            JsonNode count = explain(jdbc, GameRankingSql.count(source), parameters);
            String name = source.name().toLowerCase();
            recordPlan(games, name + "-page", page);
            recordPlan(games, name + "-page-200", deepPage);
            recordPlan(games, name + "-count", count);
            assertUsesIndex(
                    page,
                    source == Source.IGDB_PLAYERS
                            ? "ix_game_external_score_player_rank"
                            : "ix_game_community_score_rank");
            assertUsesIndex(page, "ix_release_snapshot_publication_game_period");
        }
    }

    private static GameRankingReadPort.Criteria criteria(Source source, int page) {
        return new GameRankingReadPort.Criteria(
                source,
                TODAY,
                new GameRankingReadPort.Pagination(page, 20, (long) (page - 1) * 20),
                3);
    }

    /**
     * Every game has three releases; one in ten has only a future release, one in fifty only a
     * cancelled one. Four in five carry an IGDB score drawn from a narrow band, so ties on the
     * mean are common; one in ten holds a community score.
     */
    private static void seed(JdbcTemplate jdbc, int games) {
        jdbc.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) VALUES (?::uuid, ?, now(), now(), 'product_curated', 'ranking scale fixture', true)",
                PUBLICATION_ID,
                "ranking-scale-" + games);
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) SELECT md5('ranking-platform-' || p)::uuid, 'ranking-scale-' || p, 'Ranking Platform ' || p FROM generate_series(1, 3) p");
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('92000000-0000-4000-8000-000000000386', 'ranking-scale-region', 'Mundial')");
        jdbc.update(
                "INSERT INTO catalogue.game (game_id, created_at) SELECT md5('ranking-game-' || n)::uuid, now() FROM generate_series(1, ?) n",
                games);
        jdbc.update(
                "INSERT INTO catalogue.game_snapshot (publication_id, game_id, canonical_title, slug, cover_reference, cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status) SELECT ?::uuid, md5('ranking-game-' || n)::uuid, 'Ranking Game ' || n, 'ranking-scale-game-' || n, '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Scale fallback', 'approved' FROM generate_series(1, ?) n",
                PUBLICATION_ID,
                games);
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) SELECT md5('ranking-release-' || n || '-' || r)::uuid, md5('ranking-game-' || n)::uuid, now() FROM generate_series(1, ?) n, generate_series(1, 3) r",
                games);
        jdbc.update(
                """
                INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status)
                SELECT ?::uuid, md5('ranking-release-' || n || '-' || r)::uuid, md5('ranking-game-' || n)::uuid,
                       md5('ranking-platform-' || r)::uuid, '92000000-0000-4000-8000-000000000386', 'day',
                       CASE WHEN n % 10 = 0 THEN DATE '2027-01-01' + r ELSE DATE '2005-01-01' + ((n * 3 + r) % 7000) END,
                       CASE WHEN n % 50 = 1 THEN 'cancelled' ELSE 'announced' END,
                       'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required'
                FROM generate_series(1, ?) n, generate_series(1, 3) r
                """,
                PUBLICATION_ID, games);
        jdbc.update(
                """
                INSERT INTO catalogue.game_external_score (game_id, source_name, score_kind, score, score_count, source_url, observed_at, changed_at)
                SELECT md5('ranking-game-' || n)::uuid, 'IGDB', 'player', 60 + (n % 400) / 10.0, 1 + (n * 7) % 1000,
                       'https://www.igdb.com/games/ranking-' || n, now(), now()
                FROM generate_series(1, ?) n WHERE n % 5 <> 0
                """,
                games);
        jdbc.update(
                """
                INSERT INTO catalogue.game_community_score (game_id, mean, exact_mean, rating_count)
                SELECT md5('ranking-game-' || n)::uuid, round(5 + (n % 500) / 100.0, 1), 5 + (n % 500) / 100.0, 1 + n % 7
                FROM generate_series(1, ?) n WHERE n % 10 = 3
                """,
                games);
        for (String table :
                List.of(
                        "game_snapshot",
                        "release_snapshot",
                        "game_external_score",
                        "game_community_score")) {
            jdbc.execute("ANALYZE catalogue." + table);
        }
    }

    private static TransactionTemplate readTransaction(DataSource dataSource) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(30);
        return transaction;
    }

    private static JsonNode explain(
            NamedParameterJdbcTemplate jdbc, String sql, Map<String, Object> parameters) {
        String json =
                jdbc.queryForObject(
                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, parameters, String.class);
        return OBJECT_MAPPER.readTree(json).get(0);
    }

    private static void recordPlan(int games, String operation, JsonNode plan) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "query-plans"));
        Path output = directory.resolve("ranking-" + games + "-" + operation + ".json");
        Files.writeString(
                output, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        System.out.printf(
                "Ranking %s: games=%d, executionMs=%s, rows=%s, sharedHitBlocks=%s, plan=%s%n",
                operation,
                games,
                plan.path("Execution Time"),
                plan.path("Plan").path("Actual Rows"),
                plan.path("Plan").path("Shared Hit Blocks"),
                output);
    }

    private static void assertUsesIndex(JsonNode plan, String indexName) {
        assertThat(
                        planNodes(plan).stream()
                                .map(node -> node.path("Index Name"))
                                .filter(JsonNode::isString)
                                .map(JsonNode::stringValue))
                .contains(indexName);
    }

    private static List<JsonNode> planNodes(JsonNode explain) {
        List<JsonNode> result = new ArrayList<>();
        collectPlanNodes(explain.path("Plan"), result);
        return result;
    }

    private static void collectPlanNodes(JsonNode plan, List<JsonNode> result) {
        result.add(plan);
        plan.path("Plans").forEach(child -> collectPlanNodes(child, result));
    }
}
