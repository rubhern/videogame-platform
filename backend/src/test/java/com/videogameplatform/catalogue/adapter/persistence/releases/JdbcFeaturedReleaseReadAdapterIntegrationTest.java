package com.videogameplatform.catalogue.adapter.persistence.releases;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.Item;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.domain.AttentionWeights;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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

/**
 * PostgreSQL evidence for UC-010: the selection is the head of the release explorer's Hypes order,
 * so which releases qualify for a week or a month, how their games rank, how ties and missing
 * signals behave, and which releases are presented all match that read.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JdbcFeaturedReleaseReadAdapterIntegrationTest {

    private static final String PUBLICATION = "00000000-0000-4000-8000-000000000151";
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String SWITCH_2 = "10000000-0000-4000-8000-000000000002";
    private static final String WINDOWS = "10000000-0000-4000-8000-000000000003";
    private static final String XBOX = "10000000-0000-4000-8000-000000000004";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String EUROPE = "20000000-0000-4000-8000-000000000002";
    private static final Instant OBSERVED = Instant.parse("2026-10-02T05:00:00Z");
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    /** ISO week 53 of 2032 runs from Monday 27 December 2032 to Sunday 2 January 2033. */
    private static final LocalDate WEEK_53_OF_2032 = LocalDate.parse("2032-12-27");

    private static final YearMonth MARCH_2035 = YearMonth.of(2035, 3);

    private static JdbcTemplate admin;
    private static JdbcFeaturedReleaseReadAdapter adapter;
    private static JdbcFeaturedReleaseReadAdapter emptyCatalogueAdapter;
    private static JdbcReleaseBrowseReadAdapter browseAdapter;
    private static com.videogameplatform.catalogue.adapter.persistence.attention
                    .JdbcEditorialAttentionStore
            editorialStore;

    private static final YearMonth MAY_2037 = YearMonth.of(2037, 5);

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
        seedYearEndWeek();
        seedEveryKindOfRelease();
        seedAttention();
        var writeTransaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        editorialStore =
                new com.videogameplatform.catalogue.adapter.persistence.attention
                        .JdbcEditorialAttentionStore(
                        new NamedParameterJdbcTemplate(dataSource), writeTransaction);
    }

    @Test
    void ranksByTheBlendedSignalsWithInheritanceAndReReleasesAsTheExplorerDoes() {
        // Anticipated holds all three signals. Port Of Hit claims half of its parent's Hypes and
        // Want
        // to Play, Re-release keeps half of its own Hypes, and Wished has Want to Play alone. Each
        // signal is scaled to the strongest candidate, so the two halves of 1000 Hypes tie at the
        // top of that signal (ATT-001 to ATT-003). Editorial Pick and Silent have no signal.
        assertThat(titles(criteria(MAY_2037, 6, 25)))
                .containsExactly("Anticipated", "Port Of Hit", "Re-release", "Wished");
        assertThat(explorerTitles(MAY_2037))
                .containsExactly(
                        "Anticipated",
                        "Port Of Hit",
                        "Re-release",
                        "Wished",
                        "Editorial Pick",
                        "Silent");
    }

    @Test
    void theOperatorsAttentionWeightsAreAppliedByTheRankingQuery() {
        var base = criteria(MAY_2037, 3, 25);
        // Want to Play alone: Port Of Hit's inherited half of 2e-3 leads 5e-4 and 4e-4.
        var wantToPlayOnly =
                new FeaturedReleaseReadPort.Criteria(
                        base.periodStart(),
                        base.periodEnd(),
                        base.evaluatedOn(),
                        3,
                        25,
                        List.of(),
                        new AttentionWeights(0, 1, 0, 0.5, 0.5, 0.5));
        assertThat(titles(wantToPlayOnly)).containsExactly("Port Of Hit", "Anticipated", "Wished");
        // Hypes alone with a re-release keeping all of its own: its 1000 Hypes lead the port's
        // inherited 500.
        var fullReRelease =
                new FeaturedReleaseReadPort.Criteria(
                        base.periodStart(),
                        base.periodEnd(),
                        base.evaluatedOn(),
                        2,
                        25,
                        List.of(),
                        new AttentionWeights(1, 0, 0, 0, 0.5, 1));
        assertThat(titles(fullReRelease)).containsExactly("Re-release", "Port Of Hit");
    }

    @Test
    void anEditorialListOverlappingThePeriodJoinsTheRankingWhileOthersNeverDo() {
        var june =
                editorialList(
                        LocalDate.parse("2037-06-01"),
                        LocalDate.parse("2037-06-30"),
                        Map.of(60, 100, 61, 100));
        var may = editorialList(MAY_2037.atDay(1), MAY_2037.atEndOfMonth(), Map.of(64, 90, 61, 80));
        try {
            // Half the weight is the editorial score: Wished (80) and Editorial Pick (90) overtake
            // the provider signals, and Editorial Pick now ranks with no provider signal at all.
            // June's list scores May's games but never reaches May (ATT-004, ATT-005).
            assertThat(titles(criteria(MAY_2037, 6, 25)))
                    .containsExactly(
                            "Wished", "Editorial Pick", "Anticipated", "Port Of Hit", "Re-release");
            assertThat(explorerTitles(MAY_2037))
                    .containsExactly(
                            "Wished",
                            "Editorial Pick",
                            "Anticipated",
                            "Port Of Hit",
                            "Re-release",
                            "Silent");
        } finally {
            assertThat(editorialStore.delete(may)).isTrue();
            assertThat(editorialStore.delete(june)).isTrue();
        }
        assertThat(titles(criteria(MAY_2037, 6, 25)))
                .containsExactly("Anticipated", "Port Of Hit", "Re-release", "Wished");
    }

    @Test
    void editorialListsNeverOverlapAndTheSameRangeIsReplacedWhole() {
        var first = editorialList(MAY_2037.atDay(1), MAY_2037.atEndOfMonth(), Map.of(64, 90));
        try {
            var overlapping =
                    editorialStore.save(
                            newList(
                                    LocalDate.parse("2037-05-20"),
                                    LocalDate.parse("2037-06-10"),
                                    Map.of(60, 50)),
                            java.util.Optional.empty());
            assertThat(overlapping).isEmpty();
            var replacement =
                    editorialStore
                            .save(
                                    newList(
                                            MAY_2037.atDay(1),
                                            MAY_2037.atEndOfMonth(),
                                            Map.of(60, 70, 61, 60)),
                                    java.util.Optional.of(first))
                            .orElseThrow();
            assertThat(editorialStore.find(first)).isEmpty();
            var stored = editorialStore.find(replacement).orElseThrow();
            assertThat(stored.summary().entryCount()).isEqualTo(2);
            assertThat(stored.entries())
                    .extracting(entry -> entry.title() + ":" + entry.score())
                    .containsExactly("Anticipated:70", "Wished:60");
            assertThat(editorialStore.overlapping(MAY_2037.atDay(10), MAY_2037.atDay(11)))
                    .singleElement()
                    .satisfies(list -> assertThat(list.listId()).isEqualTo(replacement));
            assertThat(editorialStore.delete(replacement)).isTrue();
        } finally {
            editorialStore.delete(first);
        }
    }

    @Test
    void editorialEntriesMatchByProviderPageOrExactTitleAndReadTheirRangeReleases() {
        admin.update(
                "UPDATE catalogue.game_snapshot SET cover_usage_mode = 'provider_cdn_reference',"
                        + " cover_source = 'IGDB', cover_reference = 'co0001',"
                        + " cover_source_url = 'https://www.igdb.com/games/anticipated'"
                        + " WHERE game_id = CAST(? AS uuid)",
                gameId(60));
        assertThat(editorialStore.findByProviderSlug(java.util.Set.of("anticipated", "missing")))
                .containsOnlyKeys("anticipated")
                .extractingByKey("anticipated")
                .satisfies(game -> assertThat(game.title()).isEqualTo("Anticipated"));
        assertThat(editorialStore.findByTitle(java.util.Set.of("  port of HIT ", "Nothing"), 2))
                .containsOnlyKeys("  port of HIT ")
                .extractingByKey("  port of HIT ")
                .satisfies(
                        games ->
                                assertThat(games)
                                        .singleElement()
                                        .satisfies(
                                                game ->
                                                        assertThat(game.gameId())
                                                                .isEqualTo(gameId(62))));
        assertThat(
                        editorialStore.releasesInRange(
                                java.util.Set.of(gameId(60), gameId(1), "not-a-uuid"),
                                MAY_2037.atDay(1),
                                MAY_2037.atEndOfMonth()))
                .containsOnlyKeys(gameId(60))
                .extractingByKey(gameId(60))
                .satisfies(
                        releases ->
                                assertThat(releases.exactDays())
                                        .containsExactly(LocalDate.parse("2037-05-10")));
    }

    @Test
    void ranksByHypesThenTheExplorerDateOrderAndKeepsOnlyTheBoundedTop() {
        var result = adapter.findFeaturedReleases(criteria(OCTOBER, 6, 25)).orElseThrow();

        // Equal signals (Charlie, Bravo) follow the explorer's date order, the earlier day first;
        // the seventh is cut.
        assertThat(result.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Juliett", "Alpha", "Delta", "Charlie", "Bravo", "Lima");
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
    void isExactlyTheHeadOfTheExplorerHypeOrderForAnyPeriodDateAndPlatforms() {
        var periods =
                List.of(
                        new Period(OCTOBER.atDay(1), OCTOBER.atEndOfMonth()),
                        new Period(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")),
                        new Period(LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-04")),
                        new Period(WEEK_53_OF_2032, WEEK_53_OF_2032.plusDays(6)),
                        new Period(MARCH_2035.atDay(1), MARCH_2035.atEndOfMonth()),
                        new Period(MARCH_2035.atDay(5), MARCH_2035.atDay(11)));
        var platformSelections = List.of(List.<String>of(), List.of(WINDOWS), List.of(PS5, XBOX));
        for (Period period : periods) {
            for (LocalDate evaluatedOn :
                    List.of(
                            period.from().minusDays(40),
                            period.from().plusDays(3),
                            period.to().plusDays(40))) {
                for (List<String> platforms : platformSelections) {
                    var featured =
                            adapter.findFeaturedReleases(
                                            new FeaturedReleaseReadPort.Criteria(
                                                    period.from(),
                                                    period.to(),
                                                    evaluatedOn,
                                                    6,
                                                    25,
                                                    platforms))
                                    .orElseThrow()
                                    .items();
                    var head = explorerHead(period, evaluatedOn, platforms);

                    assertThat(featured)
                            .as("%s evaluated on %s for %s", period, evaluatedOn, platforms)
                            .extracting(Item::canonicalTitle)
                            .containsExactlyElementsOf(
                                    head.stream()
                                            .map(ReleaseBrowseReadPort.Item::canonicalTitle)
                                            .toList());
                    for (int i = 0; i < featured.size(); i++) {
                        assertThat(featured.get(i).releases())
                                .as("%s releases in %s", featured.get(i).canonicalTitle(), period)
                                .isEqualTo(head.get(i).releases());
                    }
                }
            }
        }
    }

    @Test
    void portsRemakesEditionsAndUnknownStagesCompeteButOnlyLaunchesEnter() {
        var items =
                adapter.findFeaturedReleases(criteria(MARCH_2035, 50, 25)).orElseThrow().items();

        // Only a launch enters (ATT-007): early access, advance access and a beta never do, while a
        // release of unknown stage does. Product eligibility never excludes. Old Port launched in
        // 2018, so as a re-release it keeps half of its Hypes and falls last (ATT-003). Whiskey and
        // X-ray tie on Hypes and day, so the case-insensitive title decides; Yankee ties them on
        // Hypes with a later day. Zulu has no signal and is never ranked.
        assertThat(items)
                .extracting(Item::canonicalTitle)
                .containsExactly(
                        "Edition",
                        "Unknown First",
                        "Remake",
                        "whiskey",
                        "X-ray",
                        "Yankee",
                        "Old Port");
        // The explorer still lists the other stages, after every launch with attention.
        assertThat(explorerTitles(MARCH_2035))
                .containsSubsequence("Old Port", "Advance Access")
                .contains("Early Access", "Beta Build");
    }

    @Test
    void restrictsCandidatesToThePlatformsBeforeRankingAndLimiting() {
        // Unrestricted, the top two are Juliett and Alpha. Restricted to Windows, Mike (far below
        // them overall) takes the second slot: the ranking is that of the restricted candidates,
        // never a filtered copy of the unrestricted top.
        assertThat(adapter.findFeaturedReleases(criteria(OCTOBER, 2, 25)).orElseThrow().items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Juliett", "Alpha");
        var windows =
                adapter.findFeaturedReleases(restricted(OCTOBER, 2, List.of(WINDOWS)))
                        .orElseThrow();

        assertThat(windows.platformsSupported()).isTrue();
        assertThat(windows.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Alpha", "Mike");
        // A game presents only its releases on the restricted platforms.
        assertThat(windows.items().getFirst().releases())
                .extracting(release -> release.platform().id())
                .containsOnly(WINDOWS);
    }

    @Test
    void combinesRestrictedPlatformsWithOrAndKeepsTheRankingOrder() {
        var result =
                adapter.findFeaturedReleases(restricted(OCTOBER, 6, List.of(XBOX, WINDOWS)))
                        .orElseThrow();

        assertThat(result.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Alpha", "Bravo", "Lima", "Mike");
        assertThat(result.items())
                .flatExtracting(Item::releases)
                .extracting(release -> release.platform().id())
                .containsOnly(XBOX, WINDOWS);
    }

    @Test
    void aPlatformWithoutQualifyingReleasesYieldsNoQualifyingReleases() {
        var result =
                adapter.findFeaturedReleases(restricted(OCTOBER, 6, List.of(SWITCH_2)))
                        .orElseThrow();

        assertThat(result.platformsSupported()).isTrue();
        assertThat(result.items()).isEmpty();
        assertThat(result.qualifyingReleases()).isFalse();
    }

    @Test
    void anUnknownPlatformRanksNothingAndIsReported() {
        for (String unknown : List.of("10000000-0000-4000-8000-0000000000ff", "platform_ps5")) {
            var result =
                    adapter.findFeaturedReleases(restricted(OCTOBER, 6, List.of(PS5, unknown)))
                            .orElseThrow();

            assertThat(result.platformsSupported()).isFalse();
            assertThat(result.items()).isEmpty();
        }
    }

    @Test
    void readsTheStoredFeaturedImageAndLogoOfEachRankedGameByRole() {
        var items = adapter.findFeaturedReleases(criteria(OCTOBER, 6, 25)).orElseThrow().items();

        Item alpha = titled(items, "Alpha");
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
        Item delta = titled(items, "Delta");
        assertThat(delta.image())
                .hasValueSatisfying(
                        image -> assertThat(image.kind()).isEqualTo(ImageKind.SCREENSHOT));
        assertThat(delta.logo()).isEmpty();
        // A game without stored media still ranks; the read only completes what exists.
        assertThat(items)
                .filteredOn(item -> item != alpha && item != delta)
                .isNotEmpty()
                .allSatisfy(
                        item -> {
                            assertThat(item.image()).isEmpty();
                            assertThat(item.logo()).isEmpty();
                        });
    }

    @Test
    void afterAPeriodEndedItsEndedDatesQualifyAndNegativeEvidenceDoesNot() {
        var titles = titles(criteria(OCTOBER, 50, 25));

        // The first and the last day and a month that ended qualify, and so do a beta and a release
        // pending review. The neighbouring days, a quarter still open, cancelled evidence and a
        // delayed day that has passed never do, whatever their popularity. A game without a signal
        // stays unranked.
        assertThat(titles)
                .containsExactly("Juliett", "Alpha", "Delta", "Charlie", "Bravo", "Lima", "Mike")
                .doesNotContain("Echo", "Golf", "Hotel", "India", "Foxtrot", "December");
    }

    @Test
    void beforeAPeriodStartsOnlyExactDaysQualifyAndADelayedDayStaysExpected() {
        var titles =
                titles(
                        new FeaturedReleaseReadPort.Criteria(
                                OCTOBER.atDay(1),
                                OCTOBER.atEndOfMonth(),
                                LocalDate.parse("2026-09-01"),
                                50,
                                25));

        // Bravo is known only to the month, which has not ended: the explorer's default leaves it
        // out, and so does Golf's quarter. India's delayed day is still expected.
        assertThat(titles)
                .containsExactly("India", "Juliett", "Alpha", "Delta", "Charlie", "Lima", "Mike")
                .doesNotContain("Bravo", "Golf");
    }

    @Test
    void presentsOneQualifyingReleasePerPlatformInTheExplorerOrder() {
        Item alpha =
                titled(
                        adapter.findFeaturedReleases(criteria(OCTOBER, 50, 25))
                                .orElseThrow()
                                .items(),
                        "Alpha");

        // PlayStation 5 holds a Worldwide day and an earlier European day: the shared precedence
        // presents Worldwide before the date order. The month that ends later follows the exact
        // day,
        // and the European record stays stored, not presented.
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
                titled(
                        adapter.findFeaturedReleases(criteria(OCTOBER, 50, 1))
                                .orElseThrow()
                                .items(),
                        "Alpha");

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
        // December's only day has no signal, and Golf's quarter is still open on 1 December.
        var december =
                adapter.findFeaturedReleases(
                                new FeaturedReleaseReadPort.Criteria(
                                        LocalDate.parse("2026-12-01"),
                                        LocalDate.parse("2026-12-31"),
                                        LocalDate.parse("2026-12-01"),
                                        6,
                                        25))
                        .orElseThrow();
        var empty =
                adapter.findFeaturedReleases(criteria(YearMonth.of(2031, 2), 6, 25)).orElseThrow();

        assertThat(december.items()).isEmpty();
        assertThat(december.qualifyingReleases()).isTrue();
        assertThat(empty.items()).isEmpty();
        assertThat(empty.qualifyingReleases()).isFalse();
    }

    @Test
    void anOpenApproximateDateIsNotAQualifyingReleaseEvenWithHypes() {
        // Golf's quarter has Hypes, but before it ends the explorer's default does not list it, so
        // the period is empty rather than unranked. Once it ends it qualifies.
        var open =
                adapter.findFeaturedReleases(
                                new FeaturedReleaseReadPort.Criteria(
                                        LocalDate.parse("2026-12-14"),
                                        LocalDate.parse("2026-12-20"),
                                        LocalDate.parse("2026-12-01"),
                                        6,
                                        25))
                        .orElseThrow();
        var ended =
                adapter.findFeaturedReleases(
                                new FeaturedReleaseReadPort.Criteria(
                                        LocalDate.parse("2026-12-14"),
                                        LocalDate.parse("2026-12-20"),
                                        LocalDate.parse("2027-01-01"),
                                        6,
                                        25))
                        .orElseThrow();

        assertThat(open.items()).isEmpty();
        assertThat(open.qualifyingReleases()).isFalse();
        assertThat(ended.items()).extracting(Item::canonicalTitle).containsExactly("Golf");
    }

    @Test
    void anUpcomingWeekAdmitsOnlyExactDaysAcrossMonths() {
        // Monday 28 September to Sunday 4 October 2026, read before it starts. Echo's 30 September
        // and Charlie's 1 October are inside it; Alpha and Bravo are known to its month only, which
        // has not ended, and Alpha's days fall outside the week.
        var week =
                adapter.findFeaturedReleases(
                                week(
                                        LocalDate.parse("2026-09-28"),
                                        50,
                                        25,
                                        LocalDate.parse("2026-09-01")))
                        .orElseThrow();

        assertThat(week.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Echo", "Charlie");
        assertThat(week.items().getLast().releases())
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(new ReleaseDate.Day(LocalDate.parse("2026-10-01")));
    }

    @Test
    void aPastWeekAlsoHoldsTheEndedCoarseDatesOverlappingIt() {
        // As in the explorer, a month that has ended overlaps each of its weeks without being
        // placed
        // on an invented day.
        var week =
                adapter.findFeaturedReleases(
                                week(
                                        LocalDate.parse("2026-09-28"),
                                        50,
                                        25,
                                        LocalDate.parse("2026-11-15")))
                        .orElseThrow();

        assertThat(week.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Echo", "Alpha", "Charlie", "Bravo");
        assertThat(titled(week.items(), "Alpha").releases())
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(new ReleaseDate.Month(YearMonth.of(2026, 10)));
    }

    @Test
    void ranksAYearCrossingWeekByAttentionThenDatePresentingOnlyItsDays() {
        var week =
                adapter.findFeaturedReleases(
                                week(WEEK_53_OF_2032, 6, 25, LocalDate.parse("2032-12-01")))
                        .orElseThrow();

        // Sierra leads with the full release inside the week; its earlier beta is not a launch, so
        // it
        // neither enters the week nor makes Sierra a re-release (ATT-007). Oscar and Papa tie and
        // keep their date order across the new year. Romeo (an open month) and Tango
        // (the Monday after) stay out of the week, whatever their attention.
        assertThat(week.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Sierra", "Quebec", "Oscar", "Papa");
        assertThat(week.qualifyingReleases()).isTrue();
        // Quebec's January month precision release stays out of the week's presentation.
        assertThat(week.items().get(1).releases())
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(new ReleaseDate.Day(LocalDate.parse("2032-12-31")));
        assertThat(week.items().getLast().releases())
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(new ReleaseDate.Day(LocalDate.parse("2033-01-02")));
    }

    @Test
    void anEndedMonthHoldsItsCoarseDateBesideTheWeeksDays() {
        // December 2032 has ended, so Romeo's month precision qualifies; Papa's 2 January belongs
        // to
        // January.
        var december =
                adapter.findFeaturedReleases(criteria(YearMonth.of(2032, 12), 6, 25)).orElseThrow();

        assertThat(december.items())
                .extracting(Item::canonicalTitle)
                .containsExactly("Romeo", "Sierra", "Quebec", "Oscar");
    }

    @Test
    void aWeekHoldingOnlyAnOpenCoarserDateHasNoQualifyingReleases() {
        // Romeo is known only to December 2032, which has not ended when this week is read.
        var week =
                adapter.findFeaturedReleases(
                                week(
                                        LocalDate.parse("2032-12-06"),
                                        6,
                                        25,
                                        LocalDate.parse("2032-12-01")))
                        .orElseThrow();

        assertThat(week.items()).isEmpty();
        assertThat(week.qualifyingReleases()).isFalse();
    }

    @Test
    void anUnpublishedCatalogueIsNotReadyRatherThanEmpty() {
        assertThat(emptyCatalogueAdapter.findFeaturedReleases(criteria(OCTOBER, 6, 25))).isEmpty();
    }

    private record Period(LocalDate from, LocalDate to) {}

    /** The explorer's Hypes order for the period, keeping only the games with positive Hypes. */
    private static List<ReleaseBrowseReadPort.Item> explorerHead(
            Period period, LocalDate evaluatedOn, List<String> platforms) {
        var withHypes =
                admin.queryForList(
                        "SELECT game_id::text FROM catalogue.game_featured_evidence WHERE hypes > 0",
                        String.class);
        return browseAdapter
                .findPublishedReleases(
                        new ReleaseBrowseReadPort.Criteria(
                                new ReleaseBrowseReadPort.Window(period.from(), period.to()),
                                evaluatedOn,
                                Sort.HYPE,
                                platforms,
                                List.of(),
                                new ReleaseBrowseReadPort.Pagination(1, 100, 0),
                                false,
                                25))
                .orElseThrow()
                .items()
                .stream()
                .filter(item -> withHypes.contains(item.gameId()))
                // Destacados is the explorer's head among launches (ATT-007).
                .filter(
                        item ->
                                item.releases().stream()
                                        .anyMatch(
                                                release ->
                                                        release.stage() == ReleaseStage.FULL_RELEASE
                                                                || release.stage()
                                                                        == ReleaseStage.UNKNOWN))
                .limit(6)
                .toList();
    }

    private static List<String> titles(FeaturedReleaseReadPort.Criteria criteria) {
        return adapter.findFeaturedReleases(criteria).orElseThrow().items().stream()
                .map(Item::canonicalTitle)
                .toList();
    }

    private static Item titled(List<Item> items, String title) {
        return items.stream()
                .filter(item -> item.canonicalTitle().equals(title))
                .findFirst()
                .orElseThrow();
    }

    /** The whole month read two weeks after it ended, so its ended coarse dates qualify too. */
    private static FeaturedReleaseReadPort.Criteria criteria(
            YearMonth month, int limit, int releaseGroupLimit) {
        return new FeaturedReleaseReadPort.Criteria(
                month.atDay(1),
                month.atEndOfMonth(),
                month.atEndOfMonth().plusDays(15),
                limit,
                releaseGroupLimit);
    }

    private static FeaturedReleaseReadPort.Criteria restricted(
            YearMonth month, int limit, List<String> platformIds) {
        return new FeaturedReleaseReadPort.Criteria(
                month.atDay(1),
                month.atEndOfMonth(),
                month.atEndOfMonth().plusDays(15),
                limit,
                25,
                platformIds);
    }

    private static FeaturedReleaseReadPort.Criteria week(
            LocalDate monday, int limit, int releaseGroupLimit, LocalDate evaluatedOn) {
        return new FeaturedReleaseReadPort.Criteria(
                monday, monday.plusDays(6), evaluatedOn, limit, releaseGroupLimit);
    }

    /** Every kind of release a game can feature with, far from the other fixtures. */
    private static void seedEveryKindOfRelease() {
        kind(40, "Old Port", "900", "2018-11-14", true, "full_release", "2035-03-20");
        day(401, 40, PS5, WORLDWIDE, "2018-11-14", "released", "not_required", "full_release");
        kind(41, "Edition", "890", "2035-03-02", false, "full_release", "2035-03-20");
        kind(42, "Early Access", "880", "2035-03-03", true, "early_access", "2035-03-20");
        kind(43, "Advance Access", "870", "2035-03-20", true, "advance_access", "2035-03-20");
        kind(44, "Beta Build", "860", "2035-03-21", true, "beta", "2035-03-20");
        kind(45, "Unknown First", "850", null, true, "unknown", "2035-03-20");
        kind(46, "Remake", "840", "2035-03-22", true, "full_release", "2035-03-20");
        // Ties on Hypes: the day, then the case-insensitive title, as the explorer orders them.
        kind(47, "X-ray", "500", "2035-03-08", true, "full_release", "2035-03-08");
        kind(48, "whiskey", "500", "2035-03-08", true, "full_release", "2035-03-08");
        kind(49, "Yankee", "500", "2035-03-09", true, "full_release", "2035-03-09");
        kind(50, "Zulu", null, "2035-03-10", true, "full_release", "2035-03-10");
    }

    private static void kind(
            int game,
            String title,
            String hypes,
            String firstRelease,
            boolean eligible,
            String stage,
            String day) {
        game(game, title, hypes);
        first(game, firstRelease);
        admin.update(
                "UPDATE catalogue.game_featured_evidence SET eligible_product=? WHERE game_id=CAST(? AS uuid)",
                eligible,
                gameId(game));
        day(
                game * 10,
                game,
                game % 2 == 0 ? WINDOWS : PS5,
                WORLDWIDE,
                day,
                "announced",
                "not_required",
                stage);
    }

    /** Candidates around ISO week 53 of 2032, far from the October fixtures. */
    private static void seedYearEndWeek() {
        game(15, "Oscar", "40");
        first(15, "2032-12-27");
        day(151, 15, PS5, WORLDWIDE, "2032-12-27", "announced", "not_required", "full_release");
        game(16, "Papa", "40");
        first(16, "2033-01-02");
        day(161, 16, XBOX, WORLDWIDE, "2033-01-02", "announced", "not_required", "full_release");
        game(17, "Quebec", "60");
        first(17, "2032-12-31");
        day(171, 17, PS5, WORLDWIDE, "2032-12-31", "announced", "not_required", "full_release");
        month(172, 17, WINDOWS, WORLDWIDE, 2033, 1);
        game(18, "Romeo", "99");
        first(18, "2032-12-27");
        month(181, 18, PS5, WORLDWIDE, 2032, 12);
        game(19, "Sierra", "98");
        first(19, "2032-12-20");
        day(191, 19, PS5, EUROPE, "2032-12-20", "announced", "not_required", "beta");
        day(192, 19, PS5, WORLDWIDE, "2032-12-29", "announced", "not_required", "full_release");
        game(28, "Tango", "97");
        first(28, "2033-01-03");
        day(281, 28, PS5, WORLDWIDE, "2033-01-03", "announced", "not_required", "full_release");
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
        // Bravo and Charlie tie on popularity; Charlie's day is earlier than Bravo's month end.
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
        // An open quarter stays out by default; cancelled evidence never qualifies and a delayed
        // day
        // only while it is still expected. Pending review qualifies.
        game(7, "Golf", "99");
        quarter(71, 7, PS5, WORLDWIDE, 2026, 4);
        game(8, "Hotel", "98");
        day(81, 8, PS5, WORLDWIDE, "2026-10-12", "cancelled", "not_required", "full_release");
        game(9, "India", "97");
        day(91, 9, PS5, WORLDWIDE, "2026-10-12", "delayed", "not_required", "full_release");
        game(10, "Juliett", "96");
        day(101, 10, PS5, WORLDWIDE, "2026-10-12", "announced", "required", "full_release");
        // A beta competes like any other release.
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

    /** May 2037: one game per attention rule, each on its own Worldwide PlayStation 5 day. */
    private static void seedAttention() {
        attentionGame(60, "Anticipated", "100", "2037-05-10", 5e-4, 1e-3, null, null);
        attentionGame(61, "Wished", null, "2037-05-11", 4e-4, null, null, null);
        attentionGame(62, "Port Of Hit", null, "2037-05-12", null, null, 1000L, 2e-3);
        attentionGame(63, "Re-release", "1000", "2037-05-13", null, null, null, null);
        first(63, "2010-01-01");
        attentionGame(64, "Editorial Pick", null, "2037-05-14", null, null, null, null);
        attentionGame(65, "Silent", null, "2037-05-15", null, null, null, null);
    }

    private static void attentionGame(
            int number,
            String title,
            String hypes,
            String day,
            Double wantToPlay,
            Double visits,
            Long parentHypes,
            Double parentWantToPlay) {
        game(number, title, hypes);
        first(number, day);
        day(number * 10, number, PS5, WORLDWIDE, day, "announced", "not_required", "full_release");
        if (wantToPlay != null
                || visits != null
                || parentHypes != null
                || parentWantToPlay != null) {
            admin.update(
                    "INSERT INTO catalogue.game_attention_evidence (game_id, want_to_play, visits,"
                            + " parent_hypes, parent_want_to_play, source_name, observed_at)"
                            + " VALUES (CAST(? AS uuid), ?, ?, ?, ?, 'IGDB', ?)",
                    gameId(number),
                    wantToPlay,
                    visits,
                    parentHypes,
                    parentWantToPlay,
                    java.sql.Timestamp.from(OBSERVED));
        }
    }

    private static java.util.UUID editorialList(
            LocalDate from, LocalDate to, Map<Integer, Integer> scores) {
        return editorialStore
                .save(newList(from, to, scores), java.util.Optional.empty())
                .orElseThrow();
    }

    private static com.videogameplatform.catalogue.application.attention.port
                    .EditorialAttentionStore.NewList
            newList(LocalDate from, LocalDate to, Map<Integer, Integer> scores) {
        var entries =
                new java.util.ArrayList<
                        com.videogameplatform.catalogue.application.attention.port
                                .EditorialAttentionStore.NewEntry>();
        var games = new java.util.TreeMap<>(scores);
        int rank = 0;
        for (var score : games.entrySet()) {
            entries.add(
                    new com.videogameplatform.catalogue.application.attention.port
                            .EditorialAttentionStore.NewEntry(
                            gameId(score.getKey()),
                            ++rank,
                            score.getValue(),
                            "File title " + score.getKey(),
                            java.util.Optional.empty(),
                            List.of("https://example.test/" + score.getKey())));
        }
        return new com.videogameplatform.catalogue.application.attention.port
                .EditorialAttentionStore.NewList(
                java.util.UUID.randomUUID(),
                from,
                to,
                "editorial-anticipation@1",
                java.util.Optional.of("test-model"),
                java.util.Optional.empty(),
                OBSERVED,
                entries);
    }

    private static List<String> explorerTitles(YearMonth month) {
        return browseAdapter
                .findPublishedReleases(
                        new ReleaseBrowseReadPort.Criteria(
                                new ReleaseBrowseReadPort.Window(
                                        month.atDay(1), month.atEndOfMonth()),
                                month.atEndOfMonth().plusDays(14),
                                Sort.HYPE,
                                List.of(),
                                List.of(),
                                new ReleaseBrowseReadPort.Pagination(1, 100, 0),
                                false,
                                25))
                .orElseThrow()
                .items()
                .stream()
                .map(ReleaseBrowseReadPort.Item::canonicalTitle)
                .toList();
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
