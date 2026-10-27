package com.videogameplatform.catalogue.adapter.persistence.releases;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult.Taxonomy;
import com.videogameplatform.catalogue.application.releases.port.UpcomingHighlightsReadPort;
import com.videogameplatform.catalogue.application.releases.port.UpcomingHighlightsReadPort.Item;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * PostgreSQL evidence for UC-020: which releases of a platform scope qualify inside the horizon,
 * which games are still new to the scope, how they rank by Hypes and ties, and which release
 * presents each one.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JdbcUpcomingHighlightsReadAdapterIntegrationTest {

    private static final String PUBLICATION = "00000000-0000-4000-8000-000000000388";
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String SWITCH_2 = "10000000-0000-4000-8000-000000000002";
    private static final String WINDOWS = "10000000-0000-4000-8000-000000000003";
    private static final String XBOX = "10000000-0000-4000-8000-000000000004";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String EUROPE = "20000000-0000-4000-8000-000000000002";
    private static final Instant OBSERVED = Instant.parse("2026-10-26T05:00:00Z");

    /** The horizon of 27 October 2026 ends on 26 April 2027. */
    private static final LocalDate EVALUATED_ON = LocalDate.parse("2026-10-27");

    private static final LocalDate HORIZON_END = LocalDate.parse("2027-04-26");

    private static JdbcTemplate admin;
    private static JdbcUpcomingHighlightsReadAdapter adapter;
    private static JdbcUpcomingHighlightsReadAdapter emptyCatalogueAdapter;

    @BeforeAll
    static void prepareDatabases() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("upcoming_highlights");
        adapter = adapter(migrate(database));
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        emptyCatalogueAdapter =
                adapter(migrate(PostgreSqlTestDatabase.isolatedDatabaseName("upcoming_empty")));
        seed();
    }

    @Test
    void ranksTheQualifyingGamesOfThePlatformByHypesThenUniqueGameId() {
        var result = adapter.findUpcomingHighlights(criteria(List.of(PS5), 10)).orElseThrow();

        // Bravo and Charlie tie on Hypes; Bravo has the smaller game id. Foxtrot qualifies but has
        // no Hypes, so it is never ranked or given a value.
        assertThat(result.items())
                .extracting(Item::canonicalTitle)
                .containsExactly(
                        "Golf", "Alpha", "Delta", "Bravo", "Charlie", "Papa", "Romeo", "Whiskey");
        assertThat(result.candidates()).isTrue();
        assertThat(result.platforms()).containsExactly(new Taxonomy(PS5, "PlayStation 5"));
        assertThat(result.items())
                .allSatisfy(item -> assertThat(item.popularityObservedAt()).isEqualTo(OBSERVED));
        assertThat(result.items())
                .allSatisfy(item -> assertThat(item.genres()).hasSizeLessThanOrEqualTo(2));
    }

    @Test
    void keepsOnlyTheBoundedTopAfterRanking() {
        assertThat(adapter.findUpcomingHighlights(criteria(List.of(PS5), 3)).orElseThrow().items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Golf", "Alpha", "Delta");
    }

    @Test
    void aGameReleasedOnAnotherPlatformQualifiesWhileOneAlreadyOutOnTheScopeNeverDoes() {
        var ps5 = adapter.findUpcomingHighlights(criteria(List.of(PS5), 10)).orElseThrow();

        // Delta came out on Windows years ago and is still to come on PlayStation 5. Echo is
        // already out on PlayStation 5 in Europe, so a later regional date there is no premiere.
        assertThat(ps5.items()).extracting(Item::canonicalTitle).contains("Delta");
        assertThat(ps5.items()).extracting(Item::canonicalTitle).doesNotContain("Echo");
        // On Windows, Delta is already out; only Alpha's March 2027 month is still to come.
        var windows = adapter.findUpcomingHighlights(criteria(List.of(WINDOWS), 10)).orElseThrow();
        assertThat(windows.items()).extracting(Item::canonicalTitle).containsExactly("Alpha");
        assertThat(windows.items().getFirst().releaseDate())
                .isEqualTo(new ReleaseDate.Month(YearMonth.of(2027, 3)));
    }

    @Test
    void delayedEvidenceNeverMakesAGameAlreadyOutOnTheScope() {
        assertThat(adapter.findUpcomingHighlights(criteria(List.of(PS5), 10)).orElseThrow().items())
                .extracting(Item::canonicalTitle)
                .contains("Whiskey");
    }

    @Test
    void qualifiesOnlyDatesThatCanOnlyFallAfterTodayAndInsideTheHorizon() {
        var titles =
                adapter
                        .findUpcomingHighlights(criteria(List.of(PS5), 25))
                        .orElseThrow()
                        .items()
                        .stream()
                        .map(Item::canonicalTitle)
                        .toList();

        // Golf: Q4 2026 ends inside. Romeo: October 2026 has not ended. Papa: the last day.
        assertThat(titles).contains("Golf", "Romeo", "Papa");
        // Hotel: Q2 2027 ends after the horizon. India: a year. Juliett: unknown. Oscar: today has
        // already happened. Quebec: the day after the horizon. Sierra: September has ended.
        // Uniform: April 2027 ends after the horizon.
        assertThat(titles)
                .doesNotContain(
                        "Hotel", "India", "Juliett", "Oscar", "Quebec", "Sierra", "Uniform");
    }

    @Test
    void negativeLifecyclePendingReviewPreReleaseAndEditionsNeverQualify() {
        assertThat(adapter.findUpcomingHighlights(criteria(List.of(PS5), 25)).orElseThrow().items())
                .extracting(Item::canonicalTitle)
                .doesNotContain("Kilo", "Lima", "Mike", "November", "Tango");
    }

    @Test
    void aFamilyCombinesItsPlatformsWithOrAndUnrelatedPlatformsNeverLeakIn() {
        var ps5 = adapter.findUpcomingHighlights(criteria(List.of(PS5), 25)).orElseThrow();
        assertThat(ps5.items()).extracting(Item::canonicalTitle).doesNotContain("Victor");

        var family = adapter.findUpcomingHighlights(criteria(List.of(PS5, XBOX), 25)).orElseThrow();
        assertThat(family.items())
                .extracting(Item::canonicalTitle)
                .containsExactly(
                        "Golf", "Alpha", "Delta", "Bravo", "Charlie", "Papa", "Romeo", "Victor",
                        "Whiskey");
        assertThat(family.platforms())
                .containsExactly(
                        new Taxonomy(PS5, "PlayStation 5"), new Taxonomy(XBOX, "Xbox Series X|S"));
        assertThat(family.items()).extracting(item -> item.platform().id()).containsOnly(PS5, XBOX);
    }

    @Test
    void presentsOneQualifyingReleaseOfTheScopeByThePresentationPrecedence() {
        var items =
                adapter.findUpcomingHighlights(criteria(List.of(PS5), 10)).orElseThrow().items();

        // Alpha: Worldwide is presented ahead of an earlier European day; never the Windows month.
        Item alpha = items.get(1);
        assertThat(alpha.platform()).isEqualTo(new Taxonomy(PS5, "PlayStation 5"));
        assertThat(alpha.region().id()).isEqualTo(WORLDWIDE);
        assertThat(alpha.releaseDate())
                .isEqualTo(new ReleaseDate.Day(LocalDate.parse("2027-02-12")));
        // Golf keeps its quarter and Romeo its month: no day is ever invented.
        assertThat(items.getFirst().releaseDate()).isEqualTo(new ReleaseDate.Quarter(2026, 4));
        assertThat(items.get(6).releaseDate())
                .isEqualTo(new ReleaseDate.Month(YearMonth.of(2026, 10)));
    }

    @Test
    void readsTheStoredHeroAndCardImagesOfEachRankedGameByRole() {
        var items =
                adapter.findUpcomingHighlights(criteria(List.of(PS5), 10)).orElseThrow().items();

        Item alpha = items.get(1);
        assertThat(alpha.image())
                .hasValueSatisfying(image -> assertThat(image.kind()).isEqualTo(ImageKind.ARTWORK));
        assertThat(alpha.cardImage())
                .hasValueSatisfying(image -> assertThat(image.reference()).isEqualTo("scalpha"));
        // A game without stored media still ranks; the read only completes what exists.
        assertThat(items.getFirst().image()).isEmpty();
        assertThat(items.getFirst().cardImage()).isEmpty();
    }

    @Test
    void candidatesWithoutHypesReadAsUnrankedRatherThanEmpty() {
        var result = adapter.findUpcomingHighlights(criteria(List.of(SWITCH_2), 10)).orElseThrow();

        assertThat(result.items()).isEmpty();
        assertThat(result.candidates()).isTrue();
    }

    @Test
    void aHorizonWithoutQualifyingReleasesHasNoCandidates() {
        var result =
                adapter.findUpcomingHighlights(
                                new UpcomingHighlightsReadPort.Criteria(
                                        List.of(PS5),
                                        LocalDate.parse("2030-01-01"),
                                        LocalDate.parse("2030-06-30"),
                                        10))
                        .orElseThrow();

        assertThat(result.items()).isEmpty();
        assertThat(result.candidates()).isFalse();
    }

    @Test
    void anUnknownPlatformRanksNothingAndIsReported() {
        for (String unknown : List.of("10000000-0000-4000-8000-0000000000ff", "platform_ps5")) {
            var result =
                    adapter.findUpcomingHighlights(criteria(List.of(PS5, unknown), 10))
                            .orElseThrow();

            assertThat(result.platforms()).hasSize(1);
            assertThat(result.items()).isEmpty();
        }
    }

    @Test
    void aCatalogueNeverPublishedHasNoResult() {
        assertThat(emptyCatalogueAdapter.findUpcomingHighlights(criteria(List.of(PS5), 10)))
                .isEmpty();
    }

    private static UpcomingHighlightsReadPort.Criteria criteria(List<String> platforms, int limit) {
        return new UpcomingHighlightsReadPort.Criteria(platforms, EVALUATED_ON, HORIZON_END, limit);
    }

    private static void seed() {
        admin.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version,"
                        + " published_at, last_synchronized_at, source_kind, source_name,"
                        + " is_current) VALUES (CAST(? AS uuid), 'upcoming-v1', ?, ?,"
                        + " 'external_provider', 'IGDB', true)",
                PUBLICATION,
                java.sql.Timestamp.from(OBSERVED),
                java.sql.Timestamp.from(OBSERVED));
        // Alpha: Worldwide is presented ahead of an earlier European day on PlayStation 5.
        game(1, "Alpha", "90");
        day(11, 1, PS5, WORLDWIDE, "2027-02-12", "announced", "not_required", "full_release");
        day(12, 1, PS5, EUROPE, "2027-01-20", "announced", "not_required", "full_release");
        month(13, 1, WINDOWS, 2027, 3);
        // Bravo and Charlie tie on Hypes; Bravo has the smaller game id.
        game(2, "Bravo", "50");
        day(21, 2, PS5, WORLDWIDE, "2026-12-01", "scheduled", "not_required", "full_release");
        game(3, "Charlie", "50");
        day(31, 3, PS5, WORLDWIDE, "2026-11-01", "announced", "not_required", "full_release");
        // Delta came out on Windows in 2020 and is still to come on PlayStation 5.
        game(4, "Delta", "70");
        day(41, 4, WINDOWS, WORLDWIDE, "2020-05-01", "released", "not_required", "full_release");
        day(42, 4, PS5, WORLDWIDE, "2026-11-20", "announced", "not_required", "full_release");
        // Echo is already out on PlayStation 5 in Europe; a later Worldwide date is no premiere.
        game(5, "Echo", "200");
        day(51, 5, PS5, EUROPE, "2025-03-01", "released", "not_required", "full_release");
        day(52, 5, PS5, WORLDWIDE, "2026-12-01", "announced", "not_required", "full_release");
        // Foxtrot qualifies on PlayStation 5 and Switch 2 without Hypes.
        game(6, "Foxtrot", null);
        day(61, 6, PS5, WORLDWIDE, "2026-11-10", "announced", "not_required", "full_release");
        day(62, 6, SWITCH_2, WORLDWIDE, "2026-11-10", "announced", "not_required", "full_release");
        // Golf: Q4 2026 can only fall inside the horizon.
        game(7, "Golf", "99");
        quarter(71, 7, PS5, 2026, 4);
        // Hotel: Q2 2027 reaches past the horizon. India: a year. Juliett: unknown (TBA).
        game(8, "Hotel", "198");
        quarter(81, 8, PS5, 2027, 2);
        game(9, "India", "197");
        insertRelease(
                91,
                9,
                PS5,
                WORLDWIDE,
                "year",
                null,
                2026,
                null,
                null,
                "announced",
                "not_required",
                "full_release");
        game(10, "Juliett", "196");
        insertRelease(
                101,
                10,
                PS5,
                WORLDWIDE,
                "unknown",
                null,
                null,
                null,
                null,
                "announced",
                "not_required",
                "full_release");
        // Negative lifecycle, pending review and a pre-release stage never qualify.
        game(11, "Kilo", "195");
        day(111, 11, PS5, WORLDWIDE, "2026-12-10", "cancelled", "not_required", "full_release");
        game(12, "Lima", "194");
        day(121, 12, PS5, WORLDWIDE, "2026-12-10", "delayed", "not_required", "full_release");
        game(13, "Mike", "193");
        day(131, 13, PS5, WORLDWIDE, "2026-12-10", "announced", "required", "full_release");
        game(14, "November", "192");
        day(141, 14, PS5, WORLDWIDE, "2026-12-10", "announced", "not_required", "beta");
        // Oscar comes out today, so it has already happened; Papa on the horizon's last day;
        // Quebec the day after it.
        game(15, "Oscar", "191");
        day(151, 15, PS5, WORLDWIDE, "2026-10-27", "announced", "not_required", "full_release");
        game(16, "Papa", "35");
        day(161, 16, PS5, WORLDWIDE, "2027-04-26", "announced", "not_required", "full_release");
        game(17, "Quebec", "190");
        day(171, 17, PS5, WORLDWIDE, "2027-04-27", "announced", "not_required", "full_release");
        // Romeo: October has not ended. Sierra: September has. Uniform: April 2027 ends after.
        game(18, "Romeo", "33");
        month(181, 18, PS5, 2026, 10);
        game(19, "Sierra", "189");
        month(191, 19, PS5, 2026, 9);
        game(21, "Uniform", "187");
        month(211, 21, PS5, 2027, 4);
        // Tango is an edition, not a distinct product.
        game(20, "Tango", "188");
        admin.update(
                "UPDATE catalogue.game_featured_evidence SET eligible_product = false"
                        + " WHERE game_id = CAST(? AS uuid)",
                gameId(20));
        day(201, 20, PS5, WORLDWIDE, "2026-12-10", "announced", "not_required", "full_release");
        // Victor is only to come on Xbox Series X|S.
        game(22, "Victor", "29");
        day(221, 22, XBOX, WORLDWIDE, "2026-12-10", "announced", "not_required", "full_release");
        // Whiskey's older PlayStation 5 date was delayed: delayed evidence never occurs.
        game(23, "Whiskey", "28");
        day(231, 23, PS5, WORLDWIDE, "2025-06-01", "delayed", "not_required", "full_release");
        day(232, 23, PS5, WORLDWIDE, "2027-03-01", "announced", "not_required", "full_release");
        media(1, "image", "artwork", "aralpha", 2560, 1440);
        media(1, "card_image", "screenshot", "scalpha", 1920, 1080);
    }

    private static void media(
            int game, String role, String kind, String reference, int width, int height) {
        admin.update(
                "INSERT INTO catalogue.game_featured_media (game_id, media_role, media_kind,"
                        + " image_reference, width, height, transparent, source_name, source_url,"
                        + " observed_at) VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?, false, 'IGDB', ?,"
                        + " ?)",
                gameId(game),
                role,
                kind,
                reference,
                width,
                height,
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
                title.toLowerCase(java.util.Locale.ROOT),
                "Portada no disponible de " + title);
        admin.update(
                "INSERT INTO catalogue.game_featured_evidence (game_id, hypes, source_name,"
                        + " first_release_date, eligible_product, observed_at) VALUES (CAST(? AS"
                        + " uuid), CAST(? AS bigint), 'IGDB', DATE '2020-01-01', true, ?)",
                id,
                hypes,
                java.sql.Timestamp.from(OBSERVED));
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

    private static void month(int release, int game, String platform, int year, int month) {
        insertRelease(
                release,
                game,
                platform,
                WORLDWIDE,
                "month",
                null,
                year,
                month,
                null,
                "announced",
                "not_required",
                "full_release");
    }

    private static void quarter(int release, int game, String platform, int year, int quarter) {
        insertRelease(
                release,
                game,
                platform,
                WORLDWIDE,
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

    private static JdbcUpcomingHighlightsReadAdapter adapter(DataSource runtime) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(runtime));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
        return new JdbcUpcomingHighlightsReadAdapter(
                new NamedParameterJdbcTemplate(new JdbcTemplate(runtime)), transaction);
    }
}
