package com.videogameplatform.catalogue.adapter.persistence.releases;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.test.PostgreSqlTestDatabase;
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

        ReleaseBrowseReadPort.Result result =
                adapter.findPublishedReleases(criteria()).orElseThrow();

        JsonNode countPlan = explain(admin, countSql());
        JsonNode pagePlan = explain(admin, pageSql());
        long expectedMatches = admin.queryForObject(countSql(), Long.class);

        assertThat(result.totalItems()).isEqualTo(expectedMatches);
        assertThat(expectedMatches).isBetween(1L, rows - 1L);
        assertThat(result.items()).hasSize(20);
        assertUsesIndex(countPlan, "ix_release_browse_period");
        assertUsesIndex(pagePlan, "ix_release_browse_period");
        assertDoesNotSequentiallyScan(pagePlan, "release_snapshot", "game_snapshot");

        ReleaseBrowseReadPort.Result upcoming =
                adapter.findPublishedReleases(upcomingCriteria()).orElseThrow();
        JsonNode upcomingCountPlan = explain(admin, upcomingCountSql());
        JsonNode upcomingPagePlan = explain(admin, upcomingPageSql());
        long expectedUpcomingMatches = admin.queryForObject(upcomingCountSql(), Long.class);

        assertThat(upcoming.items()).hasSize(20);
        assertThat(upcoming.totalItems()).isEqualTo(expectedUpcomingMatches);
        assertUsesIndex(upcomingCountPlan, "ix_release_browse_period");
        assertUsesIndex(upcomingCountPlan, "ix_release_browse_unknown");
        assertUsesIndex(upcomingPagePlan, "ix_release_browse_period");
        assertDoesNotSequentiallyScan(upcomingPagePlan, "release_snapshot", "game_snapshot");
    }

    private static ReleaseBrowseReadPort.Criteria criteria() {
        return new ReleaseBrowseReadPort.Criteria(
                BrowseReleasesUseCase.View.RECENT,
                new ReleaseBrowseReadPort.Window(
                        LocalDate.of(2026, 2, 13), LocalDate.of(2026, 8, 13)),
                null,
                null,
                new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                true,
                25);
    }

    private static ReleaseBrowseReadPort.Criteria upcomingCriteria() {
        return new ReleaseBrowseReadPort.Criteria(
                BrowseReleasesUseCase.View.UPCOMING,
                new ReleaseBrowseReadPort.Window(
                        LocalDate.of(2026, 8, 13), LocalDate.of(2027, 2, 13)),
                null,
                null,
                new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                true,
                25);
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
        jdbc.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) SELECT ?::uuid, md5('release-' || n)::uuid, md5('game-' || n)::uuid, '91000000-0000-4000-8000-000000000001', '92000000-0000-4000-8000-000000000001', CASE WHEN n % 100 = 1 THEN 'unknown' ELSE 'day' END, CASE WHEN n % 100 = 1 THEN NULL ELSE DATE '2010-01-01' + (n % 7305) END, CASE WHEN n % 2 = 0 THEN 'released' ELSE 'announced' END, 'product_curated', 'scale fixture', 'release', now(), 'verified', 'not_required' FROM generate_series(1, ?) n",
                PUBLICATION_ID, rows);
        jdbc.execute("ANALYZE catalogue.game_snapshot");
        jdbc.execute("ANALYZE catalogue.release_snapshot");
    }

    private static JsonNode explain(JdbcTemplate jdbc, String sql) {
        String json =
                jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class);
        return OBJECT_MAPPER.readTree(json).get(0);
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

    // Mirrors JdbcReleaseBrowseReadAdapter's RECENT predicate: a known date that has occurred by
    // the
    // window's upper bound, excluding cancelled and delayed, overlapping the recent window.
    private static String recentPredicate() {
        return "rs.release_status NOT IN ('cancelled', 'delayed')"
                + " AND ((rs.date_precision = 'day' AND rs.exact_date <= DATE '2026-08-13')"
                + " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                + " AND rs.period_end < DATE '2026-08-13'))"
                + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                + " AND daterange(rs.period_start, rs.period_end, '[]')"
                + " && daterange(DATE '2026-02-13', DATE '2026-08-13', '[]')";
    }

    // Count and paging are over games, mirroring the grouped adapter query.
    private static final String RECENT_RELEASE_ORDER = "period_end DESC NULLS LAST, release_id";
    private static final String RECENT_GAME_ORDER =
            "period_end DESC NULLS LAST, lower(canonical_title), game_id";
    private static final String UPCOMING_PRECISION =
            "CASE date_precision WHEN 'day' THEN 1 WHEN 'month' THEN 2 WHEN 'quarter' THEN 3"
                    + " WHEN 'year' THEN 4 ELSE 5 END";
    private static final String UPCOMING_RELEASE_ORDER =
            UPCOMING_PRECISION + ", period_start ASC NULLS LAST, release_id";
    private static final String UPCOMING_GAME_ORDER =
            UPCOMING_PRECISION + ", period_start ASC NULLS LAST, lower(canonical_title), game_id";

    private static String countSql() {
        return "SELECT count(DISTINCT rs.game_id) FROM catalogue.release_snapshot rs"
                + " WHERE rs.publication_id = '"
                + PUBLICATION_ID
                + "'::uuid AND "
                + recentPredicate();
    }

    private static String pageSql() {
        return groupedPageSql(
                "rs.publication_id = '" + PUBLICATION_ID + "'::uuid AND " + recentPredicate(),
                RECENT_RELEASE_ORDER,
                RECENT_GAME_ORDER);
    }

    private static String upcomingCountSql() {
        return "SELECT count(DISTINCT rs.game_id) FROM catalogue.release_snapshot rs"
                + upcomingWhere();
    }

    private static String upcomingPageSql() {
        return groupedPageSql(
                upcomingWhere().replaceFirst("^ WHERE ", ""),
                UPCOMING_RELEASE_ORDER,
                UPCOMING_GAME_ORDER);
    }

    private static String groupedPageSql(String where, String releaseOrder, String gameOrder) {
        return "WITH filtered_release AS MATERIALIZED ("
                + "SELECT * FROM catalogue.release_snapshot rs WHERE "
                + where
                + "), game_top AS (SELECT DISTINCT ON (fr.game_id) fr.game_id, fr.period_end,"
                + " fr.period_start, fr.date_precision, gs.canonical_title FROM filtered_release fr"
                + " JOIN LATERAL (SELECT snapshot.canonical_title FROM catalogue.game_snapshot snapshot"
                + " WHERE snapshot.publication_id = fr.publication_id AND snapshot.game_id = fr.game_id"
                + " LIMIT 1) gs ON true ORDER BY fr.game_id, "
                + prefix("fr", releaseOrder)
                + "), game_page AS (SELECT * FROM game_top gt ORDER BY "
                + prefix("gt", gameOrder)
                + " LIMIT 20 OFFSET 0)"
                + " SELECT gp.game_id, rel.release_id FROM game_page gp JOIN LATERAL ("
                + "SELECT fr.release_id, fr.period_end, fr.period_start, fr.date_precision"
                + " FROM filtered_release fr WHERE fr.game_id = gp.game_id ORDER BY "
                + prefix("fr", releaseOrder)
                + " LIMIT 25) rel ON true"
                + " ORDER BY "
                + prefix("gp", gameOrder)
                + ", "
                + prefix("rel", releaseOrder);
    }

    /** Qualifies the bare column references of an order fragment with a table alias. */
    private static String prefix(String alias, String order) {
        return order.replaceAll(
                "(?<![\\w.])(period_end|period_start|release_id|game_id|canonical_title|date_precision)",
                alias + ".$1");
    }

    // Mirrors JdbcReleaseBrowseReadAdapter's UPCOMING (with unknown) predicate: a known date that
    // has not yet occurred, excluding cancelled; or a TBA unknown date that is not explicitly
    // released. Delayed releases with a valid future period stay relevant.
    private static String upcomingWhere() {
        return " WHERE rs.publication_id = '"
                + PUBLICATION_ID
                + "'::uuid AND rs.release_status <> 'cancelled'"
                + " AND ((NOT ((rs.date_precision = 'day' AND rs.exact_date <= DATE '2026-08-13')"
                + " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                + " AND rs.period_end < DATE '2026-08-13'))"
                + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                + " AND daterange(rs.period_start, rs.period_end, '[]')"
                + " && daterange(DATE '2026-08-13', DATE '2027-02-13', '[]'))"
                + " OR (rs.release_status <> 'released' AND rs.date_precision = 'unknown'))";
    }
}
