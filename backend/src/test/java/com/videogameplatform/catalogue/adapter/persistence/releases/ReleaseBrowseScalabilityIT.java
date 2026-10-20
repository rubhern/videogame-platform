package com.videogameplatform.catalogue.adapter.persistence.releases;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
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

/** Opt-in local scalability evidence; intentionally excluded from the normal test pattern. */
class ReleaseBrowseScalabilityIT {

    private static final String PUBLICATION_ID = "90000000-0000-4000-8000-000000000001";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    @Test
    void reportsThePlanWithoutMaterializingTheDatasetInJava() throws Exception {
        int rows = Integer.getInteger("release.scale.rows", 100_000);
        if (rows < 10_000 || rows > 1_000_000) {
            throw new IllegalArgumentException(
                    "release.scale.rows must be between 10000 and 1000000");
        }
        String databaseName = PostgreSqlTestDatabase.isolatedDatabaseName("release_scale_" + rows);
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
        seed(admin, rows);

        DataSource runtimeDataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(databaseName),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        var adapter =
                new JdbcReleaseBrowseReadAdapter(
                        new NamedParameterJdbcTemplate(runtimeDataSource),
                        readTransaction(runtimeDataSource));

        // The widest range a request may name, wholly before today, in the default order.
        assertScenario(
                admin,
                adapter,
                rows,
                "past-max-range-release-date",
                LocalDate.of(2026, 5, 14),
                LocalDate.of(2026, 8, 13),
                false,
                BrowseReleasesUseCase.Sort.RELEASE_DATE);
        // The widest range after today with approximate periods, ordered by Hype.
        assertScenario(
                admin,
                adapter,
                rows,
                "future-max-range-approximate-hype",
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 11, 13),
                true,
                BrowseReleasesUseCase.Sort.HYPE);
        // A past range ordered by the stored external player score.
        assertScenario(
                admin,
                adapter,
                rows,
                "past-max-range-external-player-score",
                LocalDate.of(2026, 5, 14),
                LocalDate.of(2026, 8, 13),
                false,
                BrowseReleasesUseCase.Sort.EXTERNAL_PLAYER_SCORE);
        // The default week around today, ordered by the community score projection.
        assertScenario(
                admin,
                adapter,
                rows,
                "current-week-rating",
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 16),
                false,
                BrowseReleasesUseCase.Sort.RATING);
    }

    private static void assertScenario(
            JdbcTemplate admin,
            JdbcReleaseBrowseReadAdapter adapter,
            int rows,
            String name,
            LocalDate from,
            LocalDate to,
            boolean approximate,
            BrowseReleasesUseCase.Sort sort)
            throws Exception {
        ReleaseBrowseReadPort.Result result =
                adapter.findPublishedReleases(
                                new ReleaseBrowseReadPort.Criteria(
                                        new ReleaseBrowseReadPort.Window(from, to),
                                        TODAY,
                                        sort,
                                        null,
                                        null,
                                        new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                                        approximate,
                                        25))
                        .orElseThrow();
        String where = rangeWhere(from, to, approximate);
        JsonNode countPlan = explain(admin, countSql(where));
        JsonNode pagePlan = explain(admin, pageSql(where, sort));
        long expectedMatches = admin.queryForObject(countSql(where), Long.class);
        recordPlan(rows, name + "-count", countPlan);
        recordPlan(rows, name + "-page", pagePlan);

        assertThat(expectedMatches).isBetween(1L, rows - 1L);
        assertThat(result.totalItems()).isEqualTo(expectedMatches);
        assertThat(result.items()).hasSize((int) Math.min(20, expectedMatches));
        // Every generated release shares one platform, so each game presents exactly one.
        assertThat(result.items()).allSatisfy(item -> assertThat(item.releases()).hasSize(1));
        assertThat(result.items())
                .flatExtracting(ReleaseBrowseReadPort.Item::releases)
                .noneSatisfy(
                        release ->
                                assertThat(release.releaseDate())
                                        .isInstanceOf(ReleaseDate.Unknown.class));
        assertUsesIndex(countPlan, "ix_release_browse_period");
        assertUsesIndex(pagePlan, "ix_release_browse_period");
        assertDoesNotSequentiallyScan(pagePlan, "release_snapshot", "game_snapshot");
    }

    private static TransactionTemplate readTransaction(DataSource dataSource) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
        return transaction;
    }

    private static void seed(JdbcTemplate jdbc, int rows) {
        jdbc.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) VALUES (?::uuid, ?, now(), now(), 'product_curated', 'scale fixture', true)",
                PUBLICATION_ID,
                "scale-" + rows);
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES ('91000000-0000-4000-8000-000000000001', 'scale-platform', 'Scale Platform')");
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('92000000-0000-4000-8000-000000000001', 'scale-region', 'Scale Region')");
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
        // In every block of 100 releases: one unknown, five year, five quarter and ten month
        // precision releases. The block number spreads their periods over the same twenty years as
        // the exact days, so every window overlaps partial periods the exact-day default filters.
        jdbc.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_quarter, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) SELECT ?::uuid, md5('release-' || n)::uuid, md5('game-' || n)::uuid, '91000000-0000-4000-8000-000000000001', '92000000-0000-4000-8000-000000000001', CASE WHEN n % 100 = 1 THEN 'unknown' WHEN n % 100 BETWEEN 2 AND 6 THEN 'year' WHEN n % 100 BETWEEN 7 AND 11 THEN 'quarter' WHEN n % 100 BETWEEN 12 AND 21 THEN 'month' ELSE 'day' END, CASE WHEN n % 100 = 0 OR n % 100 >= 22 THEN DATE '2010-01-01' + (n % 7305) END, CASE WHEN n % 100 BETWEEN 2 AND 21 THEN 2010 + ((n / 100) % 20) END, CASE WHEN n % 100 BETWEEN 12 AND 21 THEN 1 + ((n / 2000) % 12) END, CASE WHEN n % 100 BETWEEN 7 AND 11 THEN 1 + ((n / 2000) % 4) END, CASE WHEN n % 2 = 0 THEN 'released' ELSE 'announced' END, 'product_curated', 'scale fixture', 'release', now(), 'verified', 'not_required' FROM generate_series(1, ?) n",
                PUBLICATION_ID, rows);
        // Every tenth game also has a second release on the same platform in another region, three
        // days after its first, so presented-release selection discards real rows at scale.
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('92000000-0000-4000-8000-000000000002', 'scale-region-two', 'Scale Region Two')");
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) SELECT md5('extra-release-' || n)::uuid, md5('game-' || n)::uuid, now() FROM generate_series(10, ?, 10) n",
                rows);
        jdbc.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) SELECT ?::uuid, md5('extra-release-' || n)::uuid, md5('game-' || n)::uuid, '91000000-0000-4000-8000-000000000001', '92000000-0000-4000-8000-000000000002', 'day', DATE '2010-01-01' + (n % 7305) + 3, 'announced', 'product_curated', 'scale fixture', 'release', now(), 'verified', 'not_required' FROM generate_series(10, ?, 10) n",
                PUBLICATION_ID, rows);
        // Every third game has Hypes and every fifth a community score, so both orders mix games
        // with and without their signal.
        jdbc.update(
                "INSERT INTO catalogue.game_featured_evidence (game_id, hypes, first_release_date, eligible_product, source_name, observed_at) SELECT md5('game-' || n)::uuid, 1 + n % 997, NULL, true, 'IGDB', now() FROM generate_series(3, ?, 3) n",
                rows);
        jdbc.update(
                "INSERT INTO catalogue.game_community_score (game_id, mean, rating_count) SELECT md5('game-' || n)::uuid, 1 + (n % 91) / 10.0, 1 + n % 50 FROM generate_series(5, ?, 5) n",
                rows);
        // Every seventh game has an external player score, and every fourteenth was checked
        // without one.
        jdbc.update(
                "INSERT INTO catalogue.game_external_score (game_id, source_name, score_kind, score, score_count, source_url, observed_at, changed_at) SELECT md5('game-' || n)::uuid, 'IGDB', 'player', CASE WHEN n % 14 = 0 THEN NULL ELSE (n % 1000) / 10.0 END, CASE WHEN n % 14 = 0 THEN NULL ELSE 1 + n % 300 END, CASE WHEN n % 14 = 0 THEN NULL ELSE 'https://www.igdb.com/games/scale-' || n END, now(), now() FROM generate_series(7, ?, 7) n",
                rows);
        jdbc.execute("ANALYZE catalogue.game_external_score");
        jdbc.execute("ANALYZE catalogue.game_featured_evidence");
        jdbc.execute("ANALYZE catalogue.game_community_score");
        jdbc.execute("ANALYZE catalogue.game_snapshot");
        jdbc.execute("ANALYZE catalogue.release_snapshot");
    }

    private static JsonNode explain(JdbcTemplate jdbc, String sql) {
        String json =
                jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class);
        return OBJECT_MAPPER.readTree(json).get(0);
    }

    private static void recordPlan(int rows, String operation, JsonNode plan) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "query-plans"));
        Path output = directory.resolve("releases-" + rows + "-" + operation + ".json");
        Files.writeString(
                output, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        System.out.printf(
                "Releases %s: rows=%d, executionMs=%s, resultRows=%s, sharedHitBlocks=%s, plan=%s%n",
                operation,
                rows,
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

    // Mirrors JdbcReleaseBrowseReadAdapter's range predicate: a known period overlapping the
    // range and not cancelled; an exact day not delayed once it is today or earlier, or any exact
    // day after today; an ended partial period not delayed; and, opted in, a partial period that
    // has not ended. TBA never belongs to a range.
    private static String rangeWhere(LocalDate from, LocalDate to, boolean approximate) {
        return "rs.publication_id = '"
                + PUBLICATION_ID
                + "'::uuid AND rs.release_status <> 'cancelled'"
                + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                + " AND daterange(rs.period_start, rs.period_end, '[]')"
                + " && daterange(DATE '"
                + from
                + "', DATE '"
                + to
                + "', '[]')"
                + " AND ((rs.date_precision = 'day' AND (rs.exact_date > DATE '"
                + TODAY
                + "' OR rs.release_status <> 'delayed'))"
                + " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                + " AND rs.period_end < DATE '"
                + TODAY
                + "' AND rs.release_status <> 'delayed')"
                + (approximate
                        ? " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                                + " AND rs.period_end >= DATE '"
                                + TODAY
                                + "')"
                        : "")
                + ")";
    }

    private static final String DATE_ORDER =
            "period_end ASC, CASE date_precision WHEN 'day' THEN 1 WHEN 'month' THEN 2"
                    + " WHEN 'quarter' THEN 3 WHEN 'year' THEN 4 ELSE 5 END";
    private static final String RELEASE_ORDER = DATE_ORDER + ", release_id";
    private static final String DATE_GAME_ORDER = DATE_ORDER + ", lower(canonical_title), game_id";

    private static String countSql(String where) {
        return "SELECT count(DISTINCT rs.game_id) FROM catalogue.release_snapshot rs WHERE "
                + where;
    }

    // Mirrors the adapter: the presented release of each game and platform, under the shared
    // presentation precedence ending in the earliest date, positions the game; the chosen order's
    // one signal joins before the page is cut.
    private static String pageSql(String where, BrowseReleasesUseCase.Sort sort) {
        String signal =
                switch (sort) {
                    case RELEASE_DATE -> "SELECT gt.* FROM game_top gt";
                    case HYPE ->
                            "SELECT gt.*, fe.hypes FROM game_top gt LEFT JOIN"
                                    + " catalogue.game_featured_evidence fe ON fe.game_id = gt.game_id";
                    case RATING ->
                            "SELECT gt.*, cs.mean AS community_mean, cs.rating_count AS"
                                    + " community_rating_count FROM game_top gt LEFT JOIN"
                                    + " catalogue.game_community_score cs ON cs.game_id = gt.game_id";
                    case EXTERNAL_PLAYER_SCORE ->
                            "SELECT gt.*, round(es.score / 10, 1) AS player_score, es.score_count"
                                    + " AS player_score_count FROM game_top gt LEFT JOIN"
                                    + " catalogue.game_external_score es ON es.game_id = gt.game_id"
                                    + " AND es.source_name = 'IGDB' AND es.score_kind = 'player'"
                                    + " AND es.score IS NOT NULL";
                };
        String gameOrder =
                switch (sort) {
                    case RELEASE_DATE -> prefix("gp", DATE_GAME_ORDER);
                    case HYPE -> "gp.hypes DESC NULLS LAST, " + prefix("gp", DATE_GAME_ORDER);
                    case RATING ->
                            "gp.community_mean DESC NULLS LAST, gp.community_rating_count DESC NULLS"
                                    + " LAST, "
                                    + prefix("gp", DATE_GAME_ORDER);
                    case EXTERNAL_PLAYER_SCORE ->
                            "gp.player_score DESC NULLS LAST, gp.player_score_count DESC NULLS"
                                    + " LAST, "
                                    + prefix("gp", DATE_GAME_ORDER);
                };
        return "WITH filtered_release AS MATERIALIZED ("
                + "SELECT * FROM catalogue.release_snapshot rs WHERE "
                + where
                + "), presented_release AS MATERIALIZED (SELECT DISTINCT ON (fr.game_id,"
                + " fr.platform_id) fr.* FROM filtered_release fr JOIN catalogue.region"
                + " release_region ON release_region.region_id = fr.region_id ORDER BY fr.game_id,"
                + " fr.platform_id, "
                + ReleasePresentationOrder.of("fr", "release_region", prefix("fr", DATE_ORDER))
                + "), game_top AS (SELECT DISTINCT ON (fr.game_id) fr.game_id, fr.period_end,"
                + " fr.period_start, fr.date_precision, gs.canonical_title FROM presented_release fr"
                + " JOIN LATERAL (SELECT snapshot.canonical_title FROM catalogue.game_snapshot snapshot"
                + " WHERE snapshot.publication_id = fr.publication_id AND snapshot.game_id = fr.game_id"
                + " LIMIT 1) gs ON true ORDER BY fr.game_id, "
                + prefix("fr", RELEASE_ORDER)
                + "), game_signal AS ("
                + signal
                + "), game_page AS (SELECT * FROM game_signal gp ORDER BY "
                + gameOrder
                + " LIMIT 20 OFFSET 0)"
                + " SELECT gp.game_id, rel.release_id FROM game_page gp JOIN LATERAL ("
                + "SELECT fr.release_id, fr.period_end, fr.period_start, fr.date_precision"
                + " FROM presented_release fr WHERE fr.game_id = gp.game_id ORDER BY "
                + prefix("fr", RELEASE_ORDER)
                + " LIMIT 25) rel ON true"
                + " ORDER BY "
                + gameOrder
                + ", "
                + prefix("rel", RELEASE_ORDER);
    }

    /** Qualifies the bare column references of an order fragment with a table alias. */
    private static String prefix(String alias, String order) {
        return order.replaceAll(
                "(?<![\\w.])(period_end|period_start|release_id|game_id|canonical_title|date_precision)",
                alias + ".$1");
    }
}
