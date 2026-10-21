package com.videogameplatform.catalogue.adapter.persistence.releases;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort.DayCount;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.LocalDate;
import java.util.List;
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

/**
 * Real PostgreSQL evidence for the release calendar's counts (UC-016) over February 2028, a leap
 * month that no development seed release reaches, read on the 10th: the calendar counts from
 * that day to the month's last.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JdbcReleaseCalendarReadAdapterIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("release_calendar_adapter");
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String PC = "10000000-0000-4000-8000-000000000003";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String EUROPE = "20000000-0000-4000-8000-000000000002";
    private static final String UNKNOWN_PLATFORM = "10000000-0000-4000-8000-0000000000ff";

    private static final LocalDate TODAY = LocalDate.of(2028, 2, 10);
    private static final LocalDate FROM = TODAY;
    private static final LocalDate TO = LocalDate.of(2028, 2, 29);

    private static JdbcTemplate jdbcTemplate;
    private static JdbcReleaseCalendarReadAdapter adapter;
    private static JdbcReleaseBrowseReadAdapter explorer;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        PostgreSqlTestDatabase.createDatabase(DATABASE_NAME);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration", "classpath:db/dev-seed")
                .load()
                .migrate();
        DataSource runtimeDataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbcTemplate = new JdbcTemplate(runtimeDataSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbcTemplate);
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(runtimeDataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
        adapter = new JdbcReleaseCalendarReadAdapter(named, transaction);
        explorer = new JdbcReleaseBrowseReadAdapter(named, transaction);

        jdbcTemplate.update("UPDATE catalogue.game_featured_evidence SET hypes = 10");
        // Ensure all calendar fixtures have high hype; low and missing signals are tested below.
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_featured_evidence (game_id, hypes, eligible_product, source_name, observed_at) SELECT game_id, 10, true, 'IGDB', now() FROM catalogue.game ON CONFLICT (game_id) DO NOTHING");

        // Game 1 releases twice on the 15th and still counts once there.
        day("01", "1", PS5, EUROPE, "2028-02-15", "scheduled");
        day("02", "1", PC, WORLDWIDE, "2028-02-15", "scheduled");
        day("03", "2", PS5, WORLDWIDE, "2028-02-15", "scheduled");
        // Delayed evidence removes today, and keeps a day still ahead.
        day("04", "3", PS5, WORLDWIDE, "2028-02-10", "delayed");
        day("05", "4", PC, WORLDWIDE, "2028-02-20", "delayed");
        // Cancelled never counts; the leap day does; days gone by and other months never do.
        day("06", "5", PS5, WORLDWIDE, "2028-02-12", "cancelled");
        day("07", "6", PC, EUROPE, "2028-02-29", "scheduled");
        day("08", "7", PS5, WORLDWIDE, "2028-02-05", "released");
        day("09", "8", PS5, WORLDWIDE, "2028-03-01", "scheduled");
        // Month, quarter, year and unknown dates never count; game 9 also has an exact day.
        period("10", "9", PS5, WORLDWIDE, "month", 2028, 2, null);
        day("11", "9", PC, WORLDWIDE, "2028-02-14", "scheduled");
        period("12", "a", PC, WORLDWIDE, "quarter", 2028, null, 1);
        period("13", "b", PS5, EUROPE, "year", 2028, null, null);
        period("14", "c", PS5, WORLDWIDE, "month", 2028, 3, null);
        unknown("15", "c", PC, WORLDWIDE);
    }

    @Test
    void countsGamesPerExactDayFromTodayToTheMonthsEndOnly() {
        var result = adapter.countPublishedReleases(criteria(List.of(), List.of())).orElseThrow();

        // No day before the 10th, no approximate period and no other month is counted.
        assertThat(result.days())
                .containsExactly(
                        new DayCount(LocalDate.of(2028, 2, 14), 1, 10),
                        new DayCount(LocalDate.of(2028, 2, 15), 2, 20),
                        new DayCount(LocalDate.of(2028, 2, 20), 1, 10),
                        new DayCount(LocalDate.of(2028, 2, 29), 1, 10));
    }

    @Test
    void appliesBothFiltersToTheSameRelease() {
        var playStation =
                adapter.countPublishedReleases(criteria(List.of(PS5), List.of())).orElseThrow();
        var playStationWorldwide =
                adapter.countPublishedReleases(criteria(List.of(PS5), List.of(WORLDWIDE)))
                        .orElseThrow();

        assertThat(playStation.days())
                .containsExactly(new DayCount(LocalDate.of(2028, 2, 15), 2, 20));
        // Game 1 is on PlayStation 5 only in Europe, so the worldwide selection leaves game 2.
        assertThat(playStationWorldwide.days())
                .containsExactly(new DayCount(LocalDate.of(2028, 2, 15), 1, 10));
        // Facets stay contextual: the region selection does not narrow its own options.
        assertThat(playStationWorldwide.regions())
                .extracting(Taxonomy::id)
                .containsExactlyInAnyOrder(WORLDWIDE, EUROPE);
    }

    @Test
    void reportsAnUnknownFilterThroughItsAbsenceFromTheFacets() {
        var result =
                adapter.countPublishedReleases(criteria(List.of(UNKNOWN_PLATFORM), List.of()))
                        .orElseThrow();

        assertThat(result.platforms()).extracting(Taxonomy::id).doesNotContain(UNKNOWN_PLATFORM);
        assertThat(result.days()).isEmpty();
    }

    @Test
    void eachDayCountDoesNotExceedTheFullExplorerTotal() {
        for (List<String> platforms : List.of(List.<String>of(), List.of(PS5), List.of(PC))) {
            var calendar =
                    adapter.countPublishedReleases(criteria(platforms, List.of())).orElseThrow();
            for (LocalDate day = FROM; !day.isAfter(TO); day = day.plusDays(1)) {
                LocalDate date = day;
                long expected =
                        calendar.days().stream()
                                .filter(count -> count.date().equals(date))
                                .mapToLong(DayCount::gameCount)
                                .findFirst()
                                .orElse(0);
                var page =
                        explorer.findPublishedReleases(
                                        new ReleaseBrowseReadPort.Criteria(
                                                new ReleaseBrowseReadPort.Window(date, date),
                                                TODAY,
                                                BrowseReleasesUseCase.Sort.HYPE,
                                                platforms,
                                                List.of(),
                                                new ReleaseBrowseReadPort.Pagination(1, 3, 0),
                                                false,
                                                25))
                                .orElseThrow();
                assertThat(page.totalItems())
                        .as("all games on %s", date)
                        .isGreaterThanOrEqualTo(expected);
            }
        }
    }

    @Test
    void excludesLowMissingAndAbsentHypeFromCountsFacetsAndDayPages() {
        jdbcTemplate.update(
                "UPDATE catalogue.game_featured_evidence SET hypes = 9 WHERE game_id = ?::uuid",
                gameId("1"));
        jdbcTemplate.update(
                "UPDATE catalogue.game_featured_evidence SET hypes = NULL WHERE game_id = ?::uuid",
                gameId("2"));
        jdbcTemplate.update(
                "DELETE FROM catalogue.game_featured_evidence WHERE game_id = ?::uuid",
                gameId("6"));
        try {
            var result =
                    adapter.countPublishedReleases(criteria(List.of(), List.of())).orElseThrow();
            assertThat(result.days())
                    .containsExactly(
                            new DayCount(LocalDate.of(2028, 2, 14), 1, 10),
                            new DayCount(LocalDate.of(2028, 2, 20), 1, 10));
            assertThat(result.platforms()).extracting(Taxonomy::id).containsExactly(PC);
            assertThat(result.regions()).extracting(Taxonomy::id).containsExactly(WORLDWIDE);
            eachDayCountDoesNotExceedTheFullExplorerTotal();
            var all =
                    explorer.findPublishedReleases(
                                    new ReleaseBrowseReadPort.Criteria(
                                            new ReleaseBrowseReadPort.Window(
                                                    LocalDate.of(2028, 2, 15),
                                                    LocalDate.of(2028, 2, 15)),
                                            TODAY,
                                            BrowseReleasesUseCase.Sort.HYPE,
                                            List.of(),
                                            List.of(),
                                            new ReleaseBrowseReadPort.Pagination(1, 3, 0),
                                            false,
                                            25))
                            .orElseThrow();
            assertThat(all.totalItems()).isEqualTo(2);
        } finally {
            jdbcTemplate.update(
                    "UPDATE catalogue.game_featured_evidence SET hypes = 10 WHERE game_id IN (?::uuid, ?::uuid)",
                    gameId("1"),
                    gameId("2"));
            jdbcTemplate.update(
                    "INSERT INTO catalogue.game_featured_evidence (game_id, hypes, eligible_product, source_name, observed_at) VALUES (?::uuid, 10, true, 'IGDB', now())",
                    gameId("6"));
        }
    }

    private static ReleaseCalendarReadPort.Criteria criteria(
            List<String> platformIds, List<String> regionIds) {
        return new ReleaseCalendarReadPort.Criteria(FROM, TO, TODAY, platformIds, regionIds);
    }

    private static String releaseId(String suffix) {
        return "60000000-0000-4000-8000-0000000000" + suffix;
    }

    private static String gameId(String suffix) {
        return "30000000-0000-4000-8000-00000000000" + suffix;
    }

    private static void day(
            String release,
            String game,
            String platform,
            String region,
            String date,
            String status) {
        insert(release, game, platform, region, "day", date, null, null, null, status);
    }

    private static void period(
            String release,
            String game,
            String platform,
            String region,
            String precision,
            int year,
            Integer month,
            Integer quarter) {
        insert(release, game, platform, region, precision, null, year, month, quarter, "scheduled");
    }

    private static void unknown(String release, String game, String platform, String region) {
        insert(release, game, platform, region, "unknown", null, null, null, null, "announced");
    }

    private static void insert(
            String release,
            String game,
            String platform,
            String region,
            String precision,
            String exactDate,
            Integer year,
            Integer month,
            Integer quarter,
            String status) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                releaseId(release),
                gameId(game));
        jdbcTemplate.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_quarter, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES ('00000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?, ?, ?, ?, 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required')",
                releaseId(release),
                gameId(game),
                platform,
                region,
                precision,
                exactDate,
                year,
                month,
                quarter,
                status);
    }
}
