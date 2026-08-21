package com.videogameplatform.catalogue.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Opt-in local scalability evidence; intentionally excluded from the normal test pattern. */
class ReleaseBrowseScalabilityIT {

    private static final String PUBLICATION_ID = "90000000-0000-4000-8000-000000000001";

    @Test
    void reportsThePlanWithoutMaterializingTheDatasetInJava() throws Exception {
        int rows = Integer.getInteger("release.scale.rows", 100_000);
        if (rows < 10_000 || rows > 1_000_000) {
            throw new IllegalArgumentException(
                    "release.scale.rows must be between 10000 and 1000000");
        }
        String databaseName = "release_scale_" + rows;
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
                        new JdbcTemplate(runtimeDataSource),
                        new JdbcTransactionManager(runtimeDataSource));

        long startedAt = System.nanoTime();
        ReleaseBrowseReadPort.Result result =
                adapter.findPublishedReleases(criteria()).orElseThrow();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        List<String> countPlan = explain(admin, countSql());
        List<String> pagePlan = explain(admin, pageSql());
        long expectedMatches = admin.queryForObject(countSql(), Long.class);
        System.out.printf(
                "RELEASE_SCALE rows=%d returned=%d total=%d adapterMillis=%d%n",
                rows, result.items().size(), result.totalItems(), elapsedMillis);
        System.out.println("RELEASE_COUNT_PLAN");
        countPlan.forEach(System.out::println);
        System.out.println("RELEASE_PAGE_PLAN");
        pagePlan.forEach(System.out::println);

        assertThat(result.totalItems()).isEqualTo(expectedMatches);
        assertThat(expectedMatches).isBetween(1L, rows - 1L);
        assertThat(result.items()).hasSize(20);
        assertThat(countPlan).anyMatch(line -> line.contains("ix_release_browse_recent_period"));
        assertThat(pagePlan).anyMatch(line -> line.contains("ix_release_browse_recent_period"));
        assertThat(pagePlan).noneMatch(line -> line.contains("Seq Scan on release_snapshot"));
        assertThat(pagePlan).noneMatch(line -> line.contains("Seq Scan on game_snapshot"));

        ReleaseBrowseReadPort.Result upcoming =
                adapter.findPublishedReleases(upcomingCriteria()).orElseThrow();
        List<String> upcomingCountPlan = explain(admin, upcomingCountSql());
        List<String> upcomingPagePlan = explain(admin, upcomingPageSql());
        System.out.printf(
                "UPCOMING_RELEASE_SCALE rows=%d returned=%d total=%d%n",
                rows, upcoming.items().size(), upcoming.totalItems());
        System.out.println("UPCOMING_RELEASE_COUNT_PLAN");
        upcomingCountPlan.forEach(System.out::println);
        System.out.println("UPCOMING_RELEASE_PAGE_PLAN");
        upcomingPagePlan.forEach(System.out::println);

        assertThat(upcoming.items()).hasSize(20);
        assertThat(upcomingCountPlan)
                .anyMatch(line -> line.contains("ix_release_browse_upcoming_period"));
        assertThat(upcomingCountPlan)
                .anyMatch(line -> line.contains("ix_release_browse_upcoming_unknown"));
        assertThat(upcomingPagePlan)
                .anyMatch(line -> line.contains("ix_release_browse_upcoming_period"));
        assertThat(upcomingPagePlan)
                .noneMatch(line -> line.contains("Seq Scan on release_snapshot"));
        assertThat(upcomingPagePlan).noneMatch(line -> line.contains("Seq Scan on game_snapshot"));
    }

    private static ReleaseBrowseReadPort.Criteria criteria() {
        return new ReleaseBrowseReadPort.Criteria(
                BrowseReleasesUseCase.View.RECENT,
                new ReleaseBrowseReadPort.Window(
                        LocalDate.of(2026, 2, 13), LocalDate.of(2026, 8, 13)),
                null,
                null,
                new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                true);
    }

    private static ReleaseBrowseReadPort.Criteria upcomingCriteria() {
        return new ReleaseBrowseReadPort.Criteria(
                BrowseReleasesUseCase.View.UPCOMING,
                new ReleaseBrowseReadPort.Window(
                        LocalDate.of(2026, 8, 13), LocalDate.of(2027, 2, 13)),
                null,
                null,
                new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                true);
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
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) SELECT ?::uuid, md5('release-' || n)::uuid, md5('game-' || n)::uuid, '91000000-0000-4000-8000-000000000001', '92000000-0000-4000-8000-000000000001', CASE WHEN n % 100 = 1 THEN 'unknown' ELSE 'day' END, CASE WHEN n % 100 = 1 THEN NULL ELSE DATE '2010-01-01' + (n % 7305) END, CASE WHEN n % 2 = 0 THEN 'released' ELSE 'scheduled' END, 'product_curated', 'scale fixture', 'release', now(), 'verified', 'not_required' FROM generate_series(1, ?) n",
                PUBLICATION_ID, rows);
        jdbc.execute("ANALYZE catalogue.game_snapshot");
        jdbc.execute("ANALYZE catalogue.release_snapshot");
    }

    private static List<String> explain(JdbcTemplate jdbc, String sql) {
        return jdbc.query(
                "EXPLAIN (ANALYZE, BUFFERS) " + sql,
                (resultSet, rowNumber) -> resultSet.getString(1));
    }

    private static String countSql() {
        return "SELECT count(*) FROM catalogue.release_snapshot rs"
                + " WHERE rs.publication_id = '"
                + PUBLICATION_ID
                + "'::uuid AND rs.release_status = 'released'"
                + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                + " AND daterange(rs.period_start, rs.period_end, '[]')"
                + " && daterange(DATE '2026-02-13', DATE '2026-08-13', '[]')";
    }

    private static String pageSql() {
        return "WITH filtered_release AS MATERIALIZED ("
                + "SELECT * FROM catalogue.release_snapshot rs"
                + " WHERE rs.publication_id = '"
                + PUBLICATION_ID
                + "'::uuid AND rs.release_status = 'released'"
                + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                + " AND daterange(rs.period_start, rs.period_end, '[]')"
                + " && daterange(DATE '2026-02-13', DATE '2026-08-13', '[]'))"
                + " SELECT rs.release_id FROM filtered_release rs"
                + " JOIN LATERAL (SELECT snapshot.canonical_title"
                + " FROM catalogue.game_snapshot snapshot"
                + " WHERE snapshot.publication_id = rs.publication_id AND snapshot.game_id = rs.game_id"
                + " LIMIT 1) gs ON true"
                + " ORDER BY rs.period_end DESC NULLS LAST, lower(gs.canonical_title), rs.game_id, rs.release_id"
                + " LIMIT 20 OFFSET 0";
    }

    private static String upcomingCountSql() {
        return "SELECT count(*) FROM catalogue.release_snapshot rs" + upcomingWhere();
    }

    private static String upcomingPageSql() {
        return "WITH filtered_release AS MATERIALIZED ("
                + "SELECT * FROM catalogue.release_snapshot rs"
                + upcomingWhere()
                + ") SELECT rs.release_id FROM filtered_release rs"
                + " JOIN LATERAL (SELECT snapshot.canonical_title"
                + " FROM catalogue.game_snapshot snapshot"
                + " WHERE snapshot.publication_id = rs.publication_id AND snapshot.game_id = rs.game_id"
                + " LIMIT 1) gs ON true"
                + " ORDER BY rs.period_start ASC NULLS LAST, lower(gs.canonical_title), rs.game_id, rs.release_id"
                + " LIMIT 20 OFFSET 0";
    }

    private static String upcomingWhere() {
        return " WHERE rs.publication_id = '"
                + PUBLICATION_ID
                + "'::uuid AND rs.release_status NOT IN ('released', 'cancelled')"
                + " AND ((rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                + " AND daterange(rs.period_start, rs.period_end, '[]')"
                + " && daterange(DATE '2026-08-13', DATE '2027-02-13', '[]'))"
                + " OR rs.date_precision = 'unknown')";
    }
}
