package com.videogameplatform.catalogue.adapter.persistence.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import com.videogameplatform.catalogue.domain.CatalogueSearchText;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Opt-in local scalability evidence; intentionally excluded from the normal test pattern. */
class CatalogueSearchScalabilityIT {

    private static final String PUBLICATION_ID = "90000000-0000-4000-8000-000000000002";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void matchesRanksCountsAndPagesInPostgreSqlWithoutScanningThePublication() throws Exception {
        int games = Integer.getInteger("search.scale.games", 100_000);
        if (games < 10_000 || games > 1_000_000) {
            throw new IllegalArgumentException(
                    "search.scale.games must be between 10000 and 1000000");
        }
        String databaseName = PostgreSqlTestDatabase.isolatedDatabaseName("search_scale_" + games);
        PostgreSqlTestDatabase.createDatabase(databaseName);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(databaseName),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DataSource adminDataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.adminUrl(databaseName),
                        PostgreSqlTestDatabase.adminUsername(),
                        PostgreSqlTestDatabase.adminPassword());
        JdbcTemplate admin = new JdbcTemplate(adminDataSource);
        seed(admin, games);

        DataSource runtimeDataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(databaseName),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        var adapter =
                new JdbcGameSearchReadAdapter(
                        new NamedParameterJdbcTemplate(runtimeDataSource),
                        readTransaction(runtimeDataSource));

        GameSearchReadPort.Result result = adapter.findMatchingGames(criteria()).orElseThrow();

        var runtimeJdbc = new NamedParameterJdbcTemplate(runtimeDataSource);
        Map<String, Object> parameters = GameSearchSql.parameters(PUBLICATION_ID, criteria());
        JsonNode countPlan = explain(runtimeJdbc, GameSearchSql.COUNT, parameters);
        JsonNode pagePlan = explain(runtimeJdbc, GameSearchSql.PAGE, parameters);
        recordPlan(games, "count", countPlan);
        recordPlan(games, "page", pagePlan);

        // The query is deliberately selective but far larger than one page, and the same
        // games also match through their approved alias.
        // A page of 20 stays a page of 20 in Java however large the publication grows, and a
        // game matched by both its title and its alias is still counted and returned once.
        assertThat(result.items()).hasSize(20);
        assertThat(result.totalItems()).isEqualTo(games / 100L);
        assertThat(result.items())
                .extracting(GameSearchReadPort.Item::gameId)
                .doesNotHaveDuplicates();
        assertThat(result.items())
                .allSatisfy(item -> assertThat(item.releaseContext()).hasSizeLessThanOrEqualTo(3));
        assertThat(pagePlan.path("Plan").path("Actual Rows").asLong()).isEqualTo(60);
        // Small tables may legitimately cost less to scan. Prove indexed access at the
        // representative scale without forcing the planner or gating on machine latency.
        if (games >= 100_000) {
            assertUsesIndex(countPlan, "ix_game_snapshot_title_search");
            assertUsesIndex(countPlan, "ix_game_alias_search");
            assertUsesIndex(pagePlan, "ix_release_snapshot_publication_game_period");
            assertDoesNotSequentiallyScan(countPlan, "game_snapshot", "game_alias");
        }
    }

    private static GameSearchReadPort.Criteria criteria() {
        CatalogueSearchText text = CatalogueSearchText.of("omega");
        return new GameSearchReadPort.Criteria(
                text.normalized(), text.tokens(), new GameSearchReadPort.Pagination(1, 20, 0), 3);
    }

    private static TransactionTemplate readTransaction(DataSource dataSource) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
        return transaction;
    }

    private static void seed(JdbcTemplate jdbc, int games) {
        jdbc.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) VALUES (?::uuid, ?, now(), now(), 'product_curated', 'search scale fixture', true)",
                PUBLICATION_ID,
                "search-scale-" + games);
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES ('91000000-0000-4000-8000-000000000002', 'search-scale-platform', 'Search Scale Platform')");
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('92000000-0000-4000-8000-000000000002', 'search-scale-region', 'Search Scale Region')");
        jdbc.update(
                "INSERT INTO catalogue.game (game_id, created_at) SELECT md5('search-game-' || n)::uuid, now() FROM generate_series(1, ?) n",
                games);
        jdbc.update(
                "INSERT INTO catalogue.game_snapshot (publication_id, game_id, canonical_title, slug, cover_reference, cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status) SELECT ?::uuid, md5('search-game-' || n)::uuid, 'Scale Game ' || lpad(n::text, 6, '0') || CASE WHEN n % 100 = 0 THEN ' Omega' ELSE '' END, 'search-scale-game-' || n, '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Scale fallback', 'approved' FROM generate_series(1, ?) n",
                PUBLICATION_ID, games);
        jdbc.update(
                "INSERT INTO catalogue.game_alias (publication_id, game_id, alias, alias_kind, approval_status, source_kind, source_name) SELECT ?::uuid, md5('search-game-' || n)::uuid, 'Escala Juego ' || lpad(n::text, 6, '0') || CASE WHEN n % 100 = 0 THEN ' Omega' ELSE '' END, 'localized', CASE WHEN n % 7 = 0 THEN 'pending' ELSE 'approved' END, 'product_curated', 'search scale fixture' FROM generate_series(1, ?) n",
                PUBLICATION_ID, games);
        // Several releases per game prove the bounded release context, not a fan-out.
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) SELECT md5('search-release-' || n || '-' || r)::uuid, md5('search-game-' || n)::uuid, now() FROM generate_series(1, ?) n, generate_series(1, 5) r",
                games);
        jdbc.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) SELECT ?::uuid, md5('search-release-' || n || '-' || r)::uuid, md5('search-game-' || n)::uuid, '91000000-0000-4000-8000-000000000002', '92000000-0000-4000-8000-000000000002', 'day', DATE '2010-01-01' + ((n * 5 + r) % 7305), 'released', 'product_curated', 'search scale fixture', 'release', now(), 'verified', 'not_required' FROM generate_series(1, ?) n, generate_series(1, 5) r",
                PUBLICATION_ID, games);
        jdbc.execute("ANALYZE catalogue.game_snapshot");
        jdbc.execute("ANALYZE catalogue.game_alias");
        jdbc.execute("ANALYZE catalogue.release_snapshot");
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
        Path output = directory.resolve("search-" + games + "-" + operation + ".json");
        Files.writeString(
                output, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        System.out.printf(
                "Search %s: games=%d, executionMs=%s, rows=%s, sharedHitBlocks=%s, plan=%s%n",
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

    private static void assertDoesNotSequentiallyScan(JsonNode plan, String... relationNames) {
        List<String> prohibitedRelations = List.of(relationNames);
        assertThat(planNodes(plan))
                .noneMatch(
                        node ->
                                node.path("Node Type").isString()
                                        && "Seq Scan".equals(node.path("Node Type").stringValue())
                                        && node.path("Relation Name").isString()
                                        && prohibitedRelations.contains(
                                                node.path("Relation Name").stringValue()));
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
