package com.videogameplatform.catalogue.adapter.persistence.details;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Execution(ExecutionMode.SAME_THREAD)
class JdbcGameDetailsReadAdapterIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("game_details_adapter");
    private static final String GAME_DEATH_STRANDING = "30000000-0000-4000-8000-000000000001";
    // The seed's verified PlayStation 5 Europe day (2025-06-26).
    private static final String SEED_EUROPE = "40000000-0000-4000-8000-000000000001";
    private static final String PLAYSTATION_5 = "10000000-0000-4000-8000-000000000001";
    private static final String SWITCH_2 = "10000000-0000-4000-8000-000000000002";
    private static final String WINDOWS_PC = "10000000-0000-4000-8000-000000000003";
    private static final String XBOX_SERIES = "10000000-0000-4000-8000-000000000004";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String EUROPE = "20000000-0000-4000-8000-000000000002";
    private static final String JAPAN = "20000000-0000-4000-8000-000000000005";
    private static JdbcTemplate jdbcTemplate;
    private static JdbcGameDetailsReadAdapter adapter;

    @BeforeAll
    static void prepareDatabase() throws SQLException {
        PostgreSqlTestDatabase.createDatabase(DATABASE_NAME);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration", "classpath:db/dev-seed")
                .load()
                .migrate();
        DataSource runtime =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbcTemplate = new JdbcTemplate(runtime);
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(runtime));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        adapter =
                new JdbcGameDetailsReadAdapter(
                        new NamedParameterJdbcTemplate(runtime), transaction);
    }

    @Test
    void listsEveryReleasePlatformByPlatformWithEachPresentedReleaseFirst() {
        // The observed catalogue shapes on one game: several dates and regions on one platform,
        // an older year-only estimate, a date pending review and a cancelled record.
        String worldwideDay = "50000000-0000-4000-8000-000000000070";
        String japanDay = "50000000-0000-4000-8000-000000000071";
        String worldwideYear = "50000000-0000-4000-8000-000000000072";
        String pendingReview = "50000000-0000-4000-8000-000000000073";
        String cancelled = "50000000-0000-4000-8000-000000000074";
        String windowsDay = "50000000-0000-4000-8000-000000000075";
        String switchDay = "50000000-0000-4000-8000-000000000076";
        String xboxUnknown = "50000000-0000-4000-8000-000000000077";
        insert(
                worldwideDay,
                PLAYSTATION_5,
                WORLDWIDE,
                "day",
                "2026-03-12",
                "announced",
                "not_required");
        insert(japanDay, PLAYSTATION_5, JAPAN, "day", "2026-02-20", "announced", "not_required");
        insert(
                worldwideYear,
                PLAYSTATION_5,
                WORLDWIDE,
                "year",
                "2026",
                "announced",
                "not_required");
        insert(
                pendingReview,
                PLAYSTATION_5,
                WORLDWIDE,
                "day",
                "2025-05-01",
                "announced",
                "required");
        insert(cancelled, PLAYSTATION_5, EUROPE, "day", "2025-01-01", "cancelled", "not_required");
        insert(windowsDay, WINDOWS_PC, WORLDWIDE, "day", "2026-05-01", "announced", "not_required");
        insert(switchDay, SWITCH_2, WORLDWIDE, "day", "2026-05-01", "announced", "not_required");
        insert(xboxUnknown, XBOX_SERIES, WORLDWIDE, "unknown", null, "announced", "required");
        try {
            List<ReleaseRow> releases = adapter.find(GAME_DEATH_STRANDING).orElseThrow().releases();

            // Every stored release is returned: presentation never omits or merges evidence.
            // Platforms follow their earliest known date (PlayStation 5 from 2025, then the two
            // platforms sharing 2026-05-01 by name, then Xbox with no known date). Within a
            // platform: verified evidence, accepted exact days (Worldwide before Japan), the
            // year-only estimate, the date pending review and, last, the cancelled record.
            assertThat(releases)
                    .extracting(ReleaseRow::releaseId)
                    .containsExactly(
                            SEED_EUROPE,
                            worldwideDay,
                            japanDay,
                            worldwideYear,
                            pendingReview,
                            cancelled,
                            switchDay,
                            windowsDay,
                            xboxUnknown);

            // The first release of a platform and region is presented for that combination.
            assertThat(presentedByPlatformAndRegion(releases))
                    .containsExactly(
                            Map.entry("playstation-5/europe", SEED_EUROPE),
                            Map.entry("playstation-5/worldwide", worldwideDay),
                            Map.entry("playstation-5/japan", japanDay),
                            Map.entry("nintendo-switch-2/worldwide", switchDay),
                            Map.entry("windows-pc/worldwide", windowsDay),
                            Map.entry("xbox-series/worldwide", xboxUnknown));
        } finally {
            for (String releaseId :
                    List.of(
                            worldwideDay,
                            japanDay,
                            worldwideYear,
                            pendingReview,
                            cancelled,
                            windowsDay,
                            switchDay,
                            xboxUnknown)) {
                jdbcTemplate.update(
                        "DELETE FROM catalogue.release_snapshot WHERE release_id = ?::uuid",
                        releaseId);
                jdbcTemplate.update(
                        "DELETE FROM catalogue.game_release WHERE release_id = ?::uuid", releaseId);
            }
        }
    }

    @Test
    void fullReleaseWinsWithinItsRegionAndNegativeFullReleaseDoesNotDisplacePlayableEvidence() {
        String early = "50000000-0000-4000-8000-000000000090";
        String full = "50000000-0000-4000-8000-000000000091";
        String negative = "50000000-0000-4000-8000-000000000092";
        insert(early, PLAYSTATION_5, WORLDWIDE, "day", "2026-09-29", "announced", "not_required");
        insert(full, PLAYSTATION_5, WORLDWIDE, "day", "2026-10-02", "announced", "required");
        insert(negative, PLAYSTATION_5, EUROPE, "day", "2026-10-02", "cancelled", "not_required");
        jdbcTemplate.update(
                "UPDATE catalogue.release_snapshot SET release_stage='advance_access' WHERE release_id=?::uuid",
                early);
        jdbcTemplate.update(
                "UPDATE catalogue.release_snapshot SET release_stage='full_release' WHERE release_id IN (?::uuid,?::uuid)",
                full,
                negative);
        try {
            var releases = adapter.find(GAME_DEATH_STRANDING).orElseThrow().releases();
            assertThat(presentedByPlatformAndRegion(releases))
                    .containsEntry("playstation-5/worldwide", full)
                    .containsEntry("playstation-5/europe", SEED_EUROPE);
            assertThat(releases).extracting(ReleaseRow::releaseId).contains(early, full, negative);
            assertThat(
                            releases.stream()
                                    .filter(r -> r.releaseId().equals(full))
                                    .findFirst()
                                    .orElseThrow()
                                    .stage())
                    .isEqualTo(com.videogameplatform.catalogue.domain.ReleaseStage.FULL_RELEASE);
        } finally {
            for (String id : List.of(early, full, negative)) {
                jdbcTemplate.update(
                        "DELETE FROM catalogue.release_snapshot WHERE release_id=?::uuid", id);
                jdbcTemplate.update(
                        "DELETE FROM catalogue.game_release WHERE release_id=?::uuid", id);
            }
        }
    }

    private static Map<String, String> presentedByPlatformAndRegion(List<ReleaseRow> releases) {
        Map<String, String> presented = new LinkedHashMap<>();
        for (ReleaseRow release : releases) {
            presented.putIfAbsent(
                    release.platform().id() + "/" + release.region().id(), release.releaseId());
        }
        return presented;
    }

    private static void insert(
            String releaseId,
            String platformId,
            String regionId,
            String precision,
            String value,
            String status,
            String review) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                releaseId,
                GAME_DEATH_STRANDING);
        jdbcTemplate.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES ('00000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?, ?, 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', ?)",
                releaseId,
                GAME_DEATH_STRANDING,
                platformId,
                regionId,
                precision,
                "day".equals(precision) ? value : null,
                "year".equals(precision) ? Integer.valueOf(value) : null,
                status,
                review);
    }
}
