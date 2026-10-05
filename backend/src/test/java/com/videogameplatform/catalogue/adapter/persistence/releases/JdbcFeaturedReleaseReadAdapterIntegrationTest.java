package com.videogameplatform.catalogue.adapter.persistence.releases;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.Item;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * PostgreSQL evidence for UC-010: which releases qualify for a month, how their games rank by the
 * local popularity signal, how ties and missing signals behave, and which releases are presented.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JdbcFeaturedReleaseReadAdapterIntegrationTest {

    private static final String PUBLICATION = "00000000-0000-4000-8000-000000000151";
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String WINDOWS = "10000000-0000-4000-8000-000000000003";
    private static final String XBOX = "10000000-0000-4000-8000-000000000004";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String EUROPE = "20000000-0000-4000-8000-000000000002";
    private static final Instant OBSERVED = Instant.parse("2026-10-02T05:00:00Z");
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    private static JdbcTemplate admin;
    private static JdbcFeaturedReleaseReadAdapter adapter;
    private static JdbcFeaturedReleaseReadAdapter emptyCatalogueAdapter;
    private static JdbcReleaseBrowseReadAdapter browseAdapter;

    @BeforeAll
    static void prepareDatabases() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("featured_releases");
        var dataSource = migrate(database);
        adapter = adapter(dataSource);
        var browseTransaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        browseTransaction.setReadOnly(true);
        browseTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        browseAdapter =
                new JdbcReleaseBrowseReadAdapter(
                        new NamedParameterJdbcTemplate(dataSource), browseTransaction);
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        emptyCatalogueAdapter =
                adapter(migrate(PostgreSqlTestDatabase.isolatedDatabaseName("featured_empty")));
        seedOctober();
    }

    @Test
    void ranksByHypesThenUniqueGameIdAndKeepsOnlyTheBoundedTop() {
        var result = adapter.findFeaturedReleases(criteria(OCTOBER, 6, 25)).orElseThrow();

        // Equal signals (Bravo, Charlie) are ordered by the unique game id; the seventh is cut.
        assertThat(result.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Alpha", "Delta", "Bravo", "Charlie", "Lima", "Mike");
        assertThat(result.qualifyingReleases()).isTrue();
        assertThat(result.items().getFirst().summary()).isPresent();
        assertThat(result.items().stream().skip(1))
                .allSatisfy(item -> assertThat(item.summary()).isEmpty());
        assertThat(result.items())
                .allSatisfy(item -> assertThat(item.genres()).hasSizeLessThanOrEqualTo(2));
        assertThat(result.items())
                .allSatisfy(item -> assertThat(item.popularityObservedAt()).isEqualTo(OBSERVED));
    }

    @Test
    void readsTheStoredFeaturedImageAndLogoOfEachRankedGameByRole() {
        var items = adapter.findFeaturedReleases(criteria(OCTOBER, 6, 25)).orElseThrow().items();

        Item alpha = items.getFirst();
        assertThat(alpha.image())
                .contains(
                        new MediaReference(
                                ImageKind.ARTWORK,
                                "IGDB",
                                "aralpha",
                                2560,
                                1440,
                                false,
                                "https://www.igdb.com/games/game-1"));
        assertThat(alpha.cardImage())
                .hasValueSatisfying(image -> assertThat(image.reference()).isEqualTo("scalpha"));
        assertThat(alpha.logo())
                .contains(
                        new MediaReference(
                                ImageKind.LOGO,
                                "IGDB",
                                "loalpha",
                                900,
                                320,
                                true,
                                "https://www.igdb.com/games/game-1"));
        Item delta = items.get(1);
        assertThat(delta.image())
                .hasValueSatisfying(
                        image -> assertThat(image.kind()).isEqualTo(ImageKind.SCREENSHOT));
        assertThat(delta.logo()).isEmpty();
        // A game without stored media still ranks; the read only completes what exists.
        assertThat(items.subList(2, items.size()))
                .allSatisfy(
                        item -> {
                            assertThat(item.image()).isEmpty();
                            assertThat(item.logo()).isEmpty();
                        });
    }

    @Test
    void qualifiesOnlyKnownDatesInsideTheMonthWithoutNegativeEvidenceOrPendingReview() {
        var titles =
                adapter
                        .findFeaturedReleases(criteria(OCTOBER, 50, 25))
                        .orElseThrow()
                        .items()
                        .stream()
                        .map(Item::canonicalTitle)
                        .toList();

        // The first and the last day qualify; the neighbouring days, a quarter period, cancelled,
        // delayed and pending-review evidence never do, whatever their popularity. A game without
        // a signal stays unranked. A beta never qualifies.
        assertThat(titles)
                .containsExactly("Alpha", "Delta", "Bravo", "Charlie", "Lima", "Mike")
                .doesNotContain("Echo", "Golf", "Hotel", "India", "Juliett", "Foxtrot", "December");
    }

    @Test
    void presentsOneQualifyingReleasePerPlatformExactDayFirst() {
        Item alpha =
                adapter.findFeaturedReleases(criteria(OCTOBER, 1, 25))
                        .orElseThrow()
                        .items()
                        .getFirst();

        // PlayStation 5 holds a Worldwide day and an earlier European day: the shared precedence
        // presents Worldwide before the date order. The month precision release of another
        // platform follows the exact day, and the European record stays stored, not presented.
        assertThat(alpha.releases())
                .extracting(row -> row.platform().id() + "/" + row.region().id())
                .containsExactly(PS5 + "/" + WORLDWIDE, WINDOWS + "/" + WORLDWIDE);
        assertThat(alpha.releases())
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(
                        new ReleaseDate.Day(LocalDate.parse("2026-10-15")),
                        new ReleaseDate.Month(YearMonth.of(2026, 10)));
    }

    @Test
    void boundsTheReleasesPresentedUnderEachRankedGame() {
        Item alpha =
                adapter.findFeaturedReleases(criteria(OCTOBER, 1, 1))
                        .orElseThrow()
                        .items()
                        .getFirst();

        assertThat(alpha.releases()).singleElement();
    }

    @Test
    void ranksEachMonthFromItsOwnQualifyingReleases() {
        // Echo's first of November qualifies November even though it missed October.
        var november =
                adapter.findFeaturedReleases(criteria(YearMonth.of(2026, 11), 6, 25)).orElseThrow();

        assertThat(november.items()).extracting(Item::canonicalTitle).containsExactly("Echo");
        assertThat(november.items().getFirst().releases())
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(new ReleaseDate.Day(LocalDate.parse("2026-11-01")));
    }

    @Test
    void tellsAnUnrankedMonthApartFromAMonthWithoutQualifyingReleases() {
        var december =
                adapter.findFeaturedReleases(criteria(YearMonth.of(2026, 12), 6, 25)).orElseThrow();
        var empty =
                adapter.findFeaturedReleases(criteria(YearMonth.of(2031, 2), 6, 25)).orElseThrow();

        assertThat(december.items()).isEmpty();
        assertThat(december.qualifyingReleases()).isTrue();
        assertThat(empty.items()).isEmpty();
        assertThat(empty.qualifyingReleases()).isFalse();
    }

    @Test
    void anUnpublishedCatalogueIsNotReadyRatherThanEmpty() {
        assertThat(emptyCatalogueAdapter.findFeaturedReleases(criteria(OCTOBER, 6, 25))).isEmpty();
    }

    @Test
    void oldPortsEditionsMissingFirstDatesAndNeighbouringMonthsStayOutOfFeaturedButRemainStored() {
        for (int i = 20; i <= 27; i++) {
            game(i, "Excluded " + i, "9999");
            day(
                    i * 10,
                    i,
                    PS5,
                    WORLDWIDE,
                    "2026-10-15",
                    "announced",
                    "not_required",
                    "full_release");
        }
        first(20, "2018-11-14"); // old Game, newly ported
        first(21, "2026-09-30");
        first(22, "2026-11-01");
        first(23, null);
        admin.update(
                "UPDATE catalogue.game_featured_evidence SET eligible_product=false WHERE game_id=CAST(? AS uuid)",
                gameId(24));
        for (var stage :
                java.util.Map.of(25, "early_access", 26, "alpha", 27, "unknown").entrySet()) {
            admin.update(
                    "UPDATE catalogue.release_snapshot SET release_stage=? WHERE game_id=CAST(? AS uuid)",
                    stage.getValue(),
                    gameId(stage.getKey()));
        }
        var items = adapter.findFeaturedReleases(criteria(OCTOBER, 50, 25)).orElseThrow().items();
        assertThat(items).extracting(Item::canonicalTitle).noneMatch(t -> t.startsWith("Excluded"));
        for (var view :
                com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.View
                        .values()) {
            var normal =
                    browseAdapter
                            .findPublishedReleases(
                                    new com.videogameplatform.catalogue.application.releases.port
                                            .ReleaseBrowseReadPort.Criteria(
                                            view,
                                            new com.videogameplatform.catalogue.application.releases
                                                    .port.ReleaseBrowseReadPort.Window(
                                                    LocalDate.of(2026, 10, 1),
                                                    LocalDate.of(2026, 10, 31)),
                                            java.util.List.of(),
                                            java.util.List.of(),
                                            new com.videogameplatform.catalogue.application.releases
                                                    .port.ReleaseBrowseReadPort.Pagination(
                                                    1, 100, 0),
                                            true,
                                            25))
                            .orElseThrow();
            assertThat(normal.items())
                    .extracting(
                            com.videogameplatform.catalogue.application.releases.port
                                            .ReleaseBrowseReadPort.Item
                                    ::canonicalTitle)
                    .contains(
                            "Excluded 20",
                            "Excluded 21",
                            "Excluded 22",
                            "Excluded 23",
                            "Excluded 24",
                            "Excluded 25",
                            "Excluded 26",
                            "Excluded 27");
        }
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.game_snapshot WHERE canonical_title LIKE 'Excluded%'",
                                Integer.class))
                .isEqualTo(8);
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.release_snapshot WHERE game_id IN (SELECT game_id FROM catalogue.game_snapshot WHERE canonical_title LIKE 'Excluded%')",
                                Integer.class))
                .isEqualTo(8);
    }

    private static FeaturedReleaseReadPort.Criteria criteria(
            YearMonth month, int limit, int releaseGroupLimit) {
        return new FeaturedReleaseReadPort.Criteria(
                month.atDay(1), month.atEndOfMonth(), limit, releaseGroupLimit);
    }

    private static void seedOctober() {
        admin.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version,"
                        + " published_at, last_synchronized_at, source_kind, source_name,"
                        + " is_current) VALUES (CAST(? AS uuid), 'featured-v1', ?, ?,"
                        + " 'external_provider', 'IGDB', true)",
                PUBLICATION,
                java.sql.Timestamp.from(OBSERVED),
                java.sql.Timestamp.from(OBSERVED));
        // Alpha: two PlayStation 5 days, where Worldwide is presented ahead of an earlier European
        // day, and a Windows month.
        game(1, "Alpha", "90");
        day(11, 1, PS5, WORLDWIDE, "2026-10-15", "announced", "not_required", "full_release");
        day(12, 1, PS5, EUROPE, "2026-10-12", "announced", "not_required", "full_release");
        month(13, 1, WINDOWS, WORLDWIDE, 2026, 10);
        // Bravo and Charlie tie on popularity; Bravo has the smaller game id.
        game(2, "Bravo", "50");
        month(21, 2, XBOX, EUROPE, 2026, 10);
        game(3, "Charlie", "50");
        day(31, 3, PS5, WORLDWIDE, "2026-10-01", "announced", "not_required", "full_release");
        game(4, "Delta", "70");
        day(41, 4, PS5, WORLDWIDE, "2026-10-31", "announced", "not_required", "full_release");
        // Echo is the most visited, but its releases fall just outside the month.
        game(5, "Echo", "95");
        day(51, 5, PS5, WORLDWIDE, "2026-09-30", "announced", "not_required", "full_release");
        day(52, 5, XBOX, WORLDWIDE, "2026-11-01", "announced", "not_required", "full_release");
        // Foxtrot qualifies but has no popularity signal: it is never ranked or invented.
        game(6, "Foxtrot", null);
        day(61, 6, PS5, WORLDWIDE, "2026-10-10", "announced", "not_required", "full_release");
        // A quarter is wider than the month; negative or pending evidence never qualifies.
        game(7, "Golf", "99");
        quarter(71, 7, PS5, WORLDWIDE, 2026, 4);
        game(8, "Hotel", "98");
        day(81, 8, PS5, WORLDWIDE, "2026-10-12", "cancelled", "not_required", "full_release");
        game(9, "India", "97");
        day(91, 9, PS5, WORLDWIDE, "2026-10-12", "delayed", "not_required", "full_release");
        game(10, "Juliett", "96");
        day(101, 10, PS5, WORLDWIDE, "2026-10-12", "announced", "required", "full_release");
        // A beta is excluded even with attention.
        game(11, "Kilo", "30");
        day(111, 11, PS5, WORLDWIDE, "2026-10-07", "announced", "not_required", "beta");
        game(12, "Lima", "20");
        day(121, 12, XBOX, WORLDWIDE, "2026-10-08", "announced", "not_required", "full_release");
        game(13, "Mike", "10");
        day(131, 13, WINDOWS, WORLDWIDE, "2026-10-09", "announced", "not_required", "full_release");
        // December qualifies without any popularity signal at all.
        game(14, "December", null);
        first(5, "2026-11-01");
        first(14, "2026-12-05");
        day(141, 14, PS5, WORLDWIDE, "2026-12-05", "announced", "not_required", "full_release");
        // Alpha holds a featured artwork and a logo; Delta a screenshot only; the rest none.
        media(1, "image", "artwork", "aralpha", 2560, 1440, false);
        media(1, "card_image", "screenshot", "scalpha", 1920, 1080, false);
        media(1, "logo", "logo", "loalpha", 900, 320, true);
        media(4, "image", "screenshot", "scdelta", 1280, 720, false);
    }

    private static void first(int game, String date) {
        admin.update(
                "UPDATE catalogue.game_featured_evidence SET first_release_date=CAST(? AS date) WHERE game_id=CAST(? AS uuid)",
                date,
                gameId(game));
    }

    private static void media(
            int game,
            String role,
            String kind,
            String reference,
            int width,
            int height,
            boolean transparent) {
        admin.update(
                "INSERT INTO catalogue.game_featured_media (game_id, media_role, media_kind,"
                        + " image_reference, width, height, transparent, source_name, source_url,"
                        + " observed_at) VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?, ?, 'IGDB', ?, ?)",
                gameId(game),
                role,
                kind,
                reference,
                width,
                height,
                transparent,
                "https://www.igdb.com/games/game-" + game,
                java.sql.Timestamp.from(OBSERVED));
    }

    private static String gameId(int number) {
        return "30000000-0000-4000-8000-%012d".formatted(number);
    }

    private static void game(int number, String title, String hypes) {
        String id = gameId(number);
        admin.update(
                "INSERT INTO catalogue.game (game_id, created_at) VALUES (CAST(? AS uuid), now())",
                id);
        admin.update(
                "INSERT INTO catalogue.game_snapshot (publication_id, game_id, canonical_title,"
                        + " slug, cover_reference, cover_source, cover_usage_mode,"
                        + " cover_alternative_text, cover_usage_status) VALUES (CAST(? AS uuid),"
                        + " CAST(? AS uuid), ?, ?, '/assets/covers/fallback.svg', 'VideoGame"
                        + " Platform', 'product_owned', ?, 'approved')",
                PUBLICATION,
                id,
                title,
                title.toLowerCase(java.util.Locale.ROOT).replace(" ", "-"),
                "Portada no disponible de " + title);
        {
            admin.update(
                    "INSERT INTO catalogue.game_featured_evidence (game_id, hypes, source_name,"
                            + " first_release_date, eligible_product, observed_at) VALUES (CAST(? AS uuid),"
                            + " CAST(? AS bigint), 'IGDB', DATE '2026-10-01', true, ?)",
                    id,
                    hypes,
                    java.sql.Timestamp.from(OBSERVED));
        }
    }

    private static void day(
            int release,
            int game,
            String platform,
            String region,
            String date,
            String status,
            String review,
            String stage) {
        insertRelease(
                release,
                game,
                platform,
                region,
                "day",
                LocalDate.parse(date),
                null,
                null,
                null,
                status,
                review,
                stage);
    }

    private static void month(
            int release, int game, String platform, String region, int year, int month) {
        insertRelease(
                release,
                game,
                platform,
                region,
                "month",
                null,
                year,
                month,
                null,
                "announced",
                "not_required",
                "full_release");
    }

    private static void quarter(
            int release, int game, String platform, String region, int year, int quarter) {
        insertRelease(
                release,
                game,
                platform,
                region,
                "quarter",
                null,
                year,
                null,
                quarter,
                "announced",
                "not_required",
                "full_release");
    }

    private static void insertRelease(
            int release,
            int game,
            String platform,
            String region,
            String precision,
            LocalDate exactDate,
            Integer year,
            Integer month,
            Integer quarter,
            String status,
            String review,
            String stage) {
        String releaseId = "40000000-0000-4000-8000-%012d".formatted(release);
        admin.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES"
                        + " (CAST(? AS uuid), CAST(? AS uuid), now())",
                releaseId,
                gameId(game));
        admin.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id,"
                        + " platform_id, region_id, date_precision, exact_date, release_year,"
                        + " release_month, release_quarter, release_status, source_kind,"
                        + " source_name, source_entity_type, last_synchronized_at,"
                        + " verification_level, review_status, release_stage) VALUES (CAST(? AS"
                        + " uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS"
                        + " uuid), ?, ?, ?, ?, ?, ?, 'external_provider', 'IGDB', 'release_date',"
                        + " ?, 'provider_only', ?, ?)",
                PUBLICATION,
                releaseId,
                gameId(game),
                platform,
                region,
                precision,
                exactDate,
                year,
                month,
                quarter,
                status,
                java.sql.Timestamp.from(OBSERVED),
                review,
                stage);
    }

    private static DataSource migrate(String database) throws Exception {
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new DriverManagerDataSource(
                PostgreSqlTestDatabase.runtimeUrl(database),
                PostgreSqlTestDatabase.runtimeUsername(),
                PostgreSqlTestDatabase.runtimePassword());
    }

    private static JdbcFeaturedReleaseReadAdapter adapter(DataSource runtime) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(runtime));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
        return new JdbcFeaturedReleaseReadAdapter(
                new NamedParameterJdbcTemplate(new JdbcTemplate(runtime)), transaction);
    }
}
