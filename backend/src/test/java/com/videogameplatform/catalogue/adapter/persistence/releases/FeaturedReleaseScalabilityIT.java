package com.videogameplatform.catalogue.adapter.persistence.releases;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * Opt-in plan evidence for UC-010 (run through {@code scripts/analyze-release-browse.sh}).
 *
 * <p>Every generated game has a popularity signal and a featured image, the worst case for the
 * ranking: the query must still reach the month's releases through the shared period index and join
 * popularity and media by key, so its work follows the month, not the catalogue or those tables.
 */
class FeaturedReleaseScalabilityIT {

    private static final String PUBLICATION_ID = "90000000-0000-4000-8000-000000000151";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void ranksAMonthWithoutScanningTheCatalogueOrThePopularityTable() throws Exception {
        int rows = Integer.getInteger("release.scale.rows", 100_000);
        if (rows < 10_000 || rows > 1_000_000) {
            throw new IllegalArgumentException(
                    "release.scale.rows must be between 10000 and 1000000");
        }
        String databaseName = PostgreSqlTestDatabase.isolatedDatabaseName("featured_scale_" + rows);
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
        seed(admin, rows);

        DataSource runtime =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(databaseName),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(runtime));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
        var adapter =
                new JdbcFeaturedReleaseReadAdapter(
                        new NamedParameterJdbcTemplate(runtime), transaction);
        var criteria =
                new FeaturedReleaseReadPort.Criteria(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 6, 25);

        var result = adapter.findFeaturedReleases(criteria).orElseThrow();
        JsonNode plan = explain(admin, pageSql());
        record(rows, plan);

        assertThat(result.items()).hasSize(6);
        // Every generated release shares one platform, so each game presents exactly one.
        assertThat(result.items()).allSatisfy(item -> assertThat(item.releases()).hasSize(1));
        assertThat(indexNames(plan)).contains("ix_release_browse_period");
        assertThat(result.items()).allSatisfy(item -> assertThat(item.image()).isPresent());
        assertThat(sequentialScans(plan))
                .doesNotContain(
                        "release_snapshot",
                        "game_snapshot",
                        "game_featured_evidence",
                        "game_featured_media");
    }

    private static void seed(JdbcTemplate jdbc, int rows) {
        jdbc.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) VALUES (?::uuid, ?, now(), now(), 'product_curated', 'scale fixture', true)",
                PUBLICATION_ID,
                "featured-scale-" + rows);
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES ('91000000-0000-4000-8000-000000000151', 'featured-scale-platform', 'Scale Platform')");
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('92000000-0000-4000-8000-000000000151', 'featured-scale-region', 'Scale Region')");
        jdbc.update(
                "INSERT INTO catalogue.game (game_id, created_at) SELECT md5('game-' || n)::uuid, now() FROM generate_series(1, ?) n",
                rows);
        jdbc.update(
                "INSERT INTO catalogue.game_snapshot (publication_id, game_id, canonical_title, slug, cover_reference, cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status) SELECT ?::uuid, md5('game-' || n)::uuid, 'Scale Game ' || lpad(n::text, 7, '0'), 'scale-game-' || n, '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Scale fallback', 'approved' FROM generate_series(1, ?) n",
                PUBLICATION_ID,
                rows);
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) SELECT md5('release-' || n)::uuid, md5('game-' || n)::uuid, now() FROM generate_series(1, ?) n",
                rows);
        // Exact days spread over twenty years, every tenth release at month precision, every
        // fiftieth cancelled and every hundredth pending review, so the month filters real rows.
        jdbc.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) SELECT ?::uuid, md5('release-' || n)::uuid, md5('game-' || n)::uuid, '91000000-0000-4000-8000-000000000151', '92000000-0000-4000-8000-000000000151', CASE WHEN n % 10 = 0 THEN 'month' ELSE 'day' END, CASE WHEN n % 10 <> 0 THEN DATE '2010-01-01' + (n % 7305) END, CASE WHEN n % 10 = 0 THEN 2010 + ((n / 10) % 20) END, CASE WHEN n % 10 = 0 THEN 1 + ((n / 200) % 12) END, CASE WHEN n % 50 = 1 THEN 'cancelled' ELSE 'announced' END, 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', CASE WHEN n % 100 = 3 THEN 'required' ELSE 'not_required' END FROM generate_series(1, ?) n",
                PUBLICATION_ID, rows);
        // Every tenth game also has a second release on the same platform in another region, three
        // days later, so presented-release selection discards real rows at scale.
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('92000000-0000-4000-8000-000000000152', 'featured-scale-region-two', 'Scale Region Two')");
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) SELECT md5('extra-release-' || n)::uuid, md5('game-' || n)::uuid, now() FROM generate_series(10, ?, 10) n",
                rows);
        jdbc.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) SELECT ?::uuid, md5('extra-release-' || n)::uuid, md5('game-' || n)::uuid, '91000000-0000-4000-8000-000000000151', '92000000-0000-4000-8000-000000000152', 'day', DATE '2010-01-01' + (n % 7305) + 3, 'announced', 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required' FROM generate_series(10, ?, 10) n",
                PUBLICATION_ID, rows);
        jdbc.update("UPDATE catalogue.release_snapshot SET release_stage='full_release'");
        // Every game has a signal, with deliberate ties, so only the month bounds the ranking.
        jdbc.update(
                "INSERT INTO catalogue.game_featured_evidence (game_id, hypes, first_release_date, eligible_product, source_name, observed_at) SELECT md5('game-' || n)::uuid, (n % 997) + 1, DATE '2026-08-01', true, 'IGDB', now() FROM generate_series(1, ?) n",
                rows);
        // Every game also holds a featured image, so the media table is as large as the catalogue.
        jdbc.update(
                "INSERT INTO catalogue.game_featured_media (game_id, media_role, media_kind, image_reference, width, height, transparent, source_name, source_url, observed_at) SELECT md5('game-' || n)::uuid, 'image', 'artwork', 'ar' || n, 1920, 1080, false, 'IGDB', 'https://www.igdb.com/games/scale-game-' || n, now() FROM generate_series(1, ?) n",
                rows);
        jdbc.execute("ANALYZE catalogue.game_featured_media");
        jdbc.execute("ANALYZE catalogue.game_snapshot");
        jdbc.execute("ANALYZE catalogue.release_snapshot");
        jdbc.execute("ANALYZE catalogue.game_featured_evidence");
    }

    /** The adapter's SQL with literal parameters, so EXPLAIN plans exactly what it runs. */
    private static String pageSql() throws Exception {
        var field = JdbcFeaturedReleaseReadAdapter.class.getDeclaredField("PAGE_SQL");
        field.setAccessible(true);
        return ((String) field.get(null))
                .replace(":publicationId", "'" + PUBLICATION_ID + "'")
                .replace(":monthStart", "'2026-08-01'")
                .replace(":monthEnd", "'2026-08-31'")
                .replace(":releaseGroupLimit", "25")
                .replace(":limit", "6");
    }

    private static JsonNode explain(JdbcTemplate jdbc, String sql) {
        String json =
                jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class);
        return OBJECT_MAPPER.readTree(json).get(0);
    }

    private static void record(int rows, JsonNode plan) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "query-plans"));
        Path output = directory.resolve("featured-" + rows + "-month.json");
        Files.writeString(
                output, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        System.out.printf(
                "Featured month: rows=%d, executionMs=%s, sharedHitBlocks=%s, plan=%s%n",
                rows,
                plan.path("Execution Time"),
                plan.path("Plan").path("Shared Hit Blocks"),
                output);
    }

    private static List<String> indexNames(JsonNode plan) {
        return nodes(plan).stream()
                .map(node -> node.path("Index Name"))
                .filter(JsonNode::isString)
                .map(JsonNode::stringValue)
                .toList();
    }

    private static List<String> sequentialScans(JsonNode plan) {
        return nodes(plan).stream()
                .filter(node -> "Seq Scan".equals(node.path("Node Type").stringValue()))
                .map(node -> node.path("Relation Name").stringValue())
                .toList();
    }

    private static List<JsonNode> nodes(JsonNode explain) {
        List<JsonNode> result = new ArrayList<>();
        collect(explain.path("Plan"), result);
        return result;
    }

    private static void collect(JsonNode node, List<JsonNode> result) {
        result.add(node);
        node.path("Plans").forEach(child -> collect(child, result));
    }
}
