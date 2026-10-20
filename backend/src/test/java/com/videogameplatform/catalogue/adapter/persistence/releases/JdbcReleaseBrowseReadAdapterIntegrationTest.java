package com.videogameplatform.catalogue.adapter.persistence.releases;

import static com.videogameplatform.catalogue.adapter.persistence.releases.JdbcReleaseBrowseReadAdapterIntegrationTest.Part.FUTURE;
import static com.videogameplatform.catalogue.adapter.persistence.releases.JdbcReleaseBrowseReadAdapterIntegrationTest.Part.PAST;
import static com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort.EXTERNAL_PLAYER_SCORE;
import static com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort.HYPE;
import static com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort.RATING;
import static com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort.RELEASE_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Item;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Execution(ExecutionMode.SAME_THREAD)
class JdbcReleaseBrowseReadAdapterIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("release_browse_adapter");
    private static final String PLATFORM_PLAYSTATION_5 = "10000000-0000-4000-8000-000000000001";
    private static final String PLATFORM_WINDOWS_PC = "10000000-0000-4000-8000-000000000003";
    private static final String PLATFORM_XBOX_SERIES = "10000000-0000-4000-8000-000000000004";
    private static final String REGION_WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String REGION_EUROPE = "20000000-0000-4000-8000-000000000002";
    private static final String REGION_UNKNOWN = "20000000-0000-4000-8000-000000000003";
    private static final String REGION_JAPAN = "20000000-0000-4000-8000-000000000005";
    private static final String GAME_DEATH_STRANDING = "30000000-0000-4000-8000-000000000001";
    private static final String GAME_BANANZA = "30000000-0000-4000-8000-000000000002";
    private static final String SMALLER_ID = "50000000-0000-4000-8000-000000000050";
    private static final String LARGER_ID = "50000000-0000-4000-8000-000000000051";
    private static final String PRESENTED_A = "50000000-0000-4000-8000-000000000060";
    private static final String PRESENTED_B = "50000000-0000-4000-8000-000000000061";
    private static final String PRESENTED_C = "50000000-0000-4000-8000-000000000062";
    private static final String PRESENTED_D = "50000000-0000-4000-8000-000000000063";
    private static final String GAME_FABLE = "30000000-0000-4000-8000-000000000007";
    private static final String GAME_CRIMSON_DESERT = "30000000-0000-4000-8000-00000000000a";
    private static final String GAME_SUBNAUTICA = "30000000-0000-4000-8000-00000000000b";
    private static final String GAME_WOLVERINE = "30000000-0000-4000-8000-00000000000c";

    /** The trusted evaluation date every range below is read against, unless a case moves it. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    private static JdbcTemplate jdbcTemplate;
    private static JdbcTemplate adminJdbcTemplate;
    private static DataSource runtimeDataSource;
    private static JdbcReleaseBrowseReadAdapter adapter;

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
        runtimeDataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbcTemplate = new JdbcTemplate(runtimeDataSource);
        adminJdbcTemplate =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        adapter =
                new JdbcReleaseBrowseReadAdapter(
                        new NamedParameterJdbcTemplate(jdbcTemplate),
                        readTransaction(runtimeDataSource, 5));
    }

    @Test
    void groupsReleasesByGameCountsGamesAndPagesOverGamesInPostgreSql() {
        var firstPage = adapter.findPublishedReleases(criteria(PAST, 1, 1)).orElseThrow();
        var secondPage = adapter.findPublishedReleases(criteria(PAST, 2, 1)).orElseThrow();

        assertThat(firstPage.publicationVersion())
                .isEqualTo(
                        adminJdbcTemplate.queryForObject(
                                "SELECT catalogue_version FROM catalogue.catalogue_publication WHERE is_current",
                                String.class));
        // Five games hold releases that happened in the range even though eight releases do; count
        // and paging are over games.
        assertThat(firstPage.totalItems()).isEqualTo(5);
        assertThat(firstPage.items()).singleElement();
        assertThat(secondPage.items()).singleElement();

        Item requiem = firstPage.items().getFirst();
        assertThat(requiem.canonicalTitle()).isEqualTo("Resident Evil Requiem");
        // The one game keeps both of its releases, preserved, distinct and earliest first.
        assertThat(requiem.releases())
                .extracting(ReleaseRow::releaseId)
                .containsExactly(
                        "40000000-0000-4000-8000-000000000005",
                        "40000000-0000-4000-8000-000000000009");
        assertThat(
                        requiem.releases().stream()
                                .map(row -> row.platform().id() + "/" + row.region().id()))
                .containsExactly(
                        PLATFORM_PLAYSTATION_5 + "/" + REGION_EUROPE,
                        PLATFORM_WINDOWS_PC + "/" + REGION_WORLDWIDE);

        // The next game is a different one; no game repeats across pages.
        assertThat(secondPage.items().getFirst().canonicalTitle()).isEqualTo("Subnautica 2");
        assertThat(secondPage.items().getFirst().gameId()).isNotEqualTo(requiem.gameId());
    }

    @Test
    void neverPlacesAnUnknownDateInARangeEvenWithApproximateDates() {
        var result =
                adapter.findPublishedReleases(criteria(FUTURE, 1, 20, null, null, true))
                        .orElseThrow();

        assertThat(result.items()).hasSize(5);
        List<ReleaseRow> flat = flatten(result);
        assertThat(flat).hasSize(7);
        assertThat(flat)
                .allSatisfy(
                        row ->
                                assertThat(row.releaseDate())
                                        .isNotInstanceOf(ReleaseDate.Unknown.class));
        // The Witcher IV still appears through its known year; its TBA release stays stored.
        assertThat(result.items().getLast().canonicalTitle()).isEqualTo("The Witcher IV");
        assertThat(result.items().getLast().releases())
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(new ReleaseDate.YearOnly(Year.of(2027)));
    }

    @ParameterizedTest(name = "{0} (approximate dates: {3}) with platform={1} and region={2}")
    @MethodSource("filterCombinations")
    void composesOptionalFiltersOverGamesWithoutChangingCountOrOrder(
            Part part,
            String platformId,
            String regionId,
            boolean includeApproximateDates,
            List<String> expectedGameTitles) {
        var result =
                adapter.findPublishedReleases(
                                criteria(
                                        part, 1, 20, platformId, regionId, includeApproximateDates))
                        .orElseThrow();

        assertThat(result.totalItems()).isEqualTo(expectedGameTitles.size());
        assertThat(result.items().stream().map(Item::canonicalTitle).toList())
                .isEqualTo(expectedGameTitles);
        // A game never appears twice on the page.
        assertThat(result.items().stream().map(Item::gameId).distinct().count())
                .isEqualTo(result.items().size());
    }

    @Test
    void combinesMultipleValuesWithinADimensionUsingOr() {
        var byPlatform =
                adapter.findPublishedReleases(
                                multi(
                                        List.of(PLATFORM_PLAYSTATION_5, PLATFORM_XBOX_SERIES),
                                        List.of()))
                        .orElseThrow();
        assertThat(titles(byPlatform))
                .containsExactly("Resident Evil Requiem", "Subnautica 2", "Pragmata");

        var byRegion =
                adapter.findPublishedReleases(
                                multi(List.of(), List.of(REGION_WORLDWIDE, REGION_JAPAN)))
                        .orElseThrow();
        assertThat(titles(byRegion))
                .containsExactly(
                        "Resident Evil Requiem",
                        "Metroid Prime 4: Beyond",
                        "Crimson Desert",
                        "Pragmata");
    }

    @Test
    void distinguishesUnfilteredTodasFromTheConcreteWorldwideRegion() {
        var todas = adapter.findPublishedReleases(multi(List.of(), List.of())).orElseThrow();
        assertThat(todas.totalItems()).isEqualTo(5);

        var worldwide =
                adapter.findPublishedReleases(multi(List.of(), List.of(REGION_WORLDWIDE)))
                        .orElseThrow();
        assertThat(titles(worldwide))
                .containsExactly("Resident Evil Requiem", "Crimson Desert", "Pragmata");
    }

    @Test
    void availablePlatformFacetIsNotNarrowedByItsOwnSelection() {
        var result =
                adapter.findPublishedReleases(multi(List.of(PLATFORM_PLAYSTATION_5), List.of()))
                        .orElseThrow();
        // The platform facet reflects the range, never the active platform set, so an unselected
        // platform present in the range stays available to add.
        assertThat(result.platforms())
                .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                .contains(PLATFORM_PLAYSTATION_5, PLATFORM_XBOX_SERIES);
    }

    @Test
    void keepsASelectedValidRegionRepresentableEvenWithoutAReleaseInContext() {
        // Japan has no PlayStation 5 release in the range, but a selected valid region must remain
        // representable so the visitor can remove it.
        var result =
                adapter.findPublishedReleases(
                                multi(List.of(PLATFORM_PLAYSTATION_5), List.of(REGION_JAPAN)))
                        .orElseThrow();
        assertThat(result.items()).isEmpty();
        assertThat(result.regions())
                .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                .contains(REGION_JAPAN);
    }

    @Test
    void showsOnlyExactDaysAfterTodayUntilApproximateDatesAreIncluded() {
        var exact = adapter.findPublishedReleases(criteria(FUTURE, 1, 20)).orElseThrow();

        // Month, quarter and year releases still to come are absent by default.
        assertThat(exact.totalItems()).isEqualTo(1);
        assertThat(titles(exact)).containsExactly("Marvel's Wolverine");
        assertThat(flatten(exact))
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.releaseDate())
                                        .isEqualTo(new ReleaseDate.Day(LocalDate.of(2026, 9, 25))));

        // The same stored publication still serves them unchanged once the visitor opts in, each
        // at its own precision and after the exact days ending no later.
        var approximate =
                adapter.findPublishedReleases(criteria(FUTURE, 1, 20, null, null, true))
                        .orElseThrow();
        assertThat(approximate.totalItems()).isEqualTo(5);
        assertThat(flatten(approximate))
                .extracting(ReleaseRow::releaseDate)
                .containsExactly(
                        new ReleaseDate.Day(LocalDate.of(2026, 9, 25)),
                        new ReleaseDate.Month(YearMonth.of(2026, 10)),
                        new ReleaseDate.Month(YearMonth.of(2026, 10)),
                        new ReleaseDate.Quarter(2026, 4),
                        new ReleaseDate.Quarter(2027, 1),
                        new ReleaseDate.YearOnly(Year.of(2027)),
                        new ReleaseDate.YearOnly(Year.of(2027)));
        assertThat(titles(approximate))
                .containsExactly(
                        "Marvel's Wolverine",
                        "Crimson Desert",
                        "Subnautica 2",
                        "Fable",
                        "The Witcher IV");
    }

    @Test
    void keepsEndedApproximatePeriodsInThePastPartWithoutTheOptIn() {
        // A quarter that ended before today and overlaps the range happened inside it, so it
        // belongs to the range without the opt-in, at its own precision.
        var result =
                adapter.findPublishedReleases(
                                range(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), false))
                        .orElseThrow();
        assertThat(titles(result)).containsExactly("Pragmata");
        assertThat(flatten(result))
                .extracting(ReleaseRow::releaseDate)
                .containsOnly(new ReleaseDate.Quarter(2026, 2));

        // The opt-in changes nothing for a range wholly before today.
        assertThat(
                        titles(
                                adapter.findPublishedReleases(
                                                range(
                                                        LocalDate.of(2026, 6, 1),
                                                        LocalDate.of(2026, 6, 30),
                                                        true))
                                        .orElseThrow()))
                .containsExactly("Pragmata");
    }

    @Test
    void readsAMixedRangeAroundTodayWithBothClassifications() {
        Fixture happened = day(PRESENTED_A, PLATFORM_PLAYSTATION_5, REGION_EUROPE, "2026-08-05");
        Fixture delayedPast =
                day(PRESENTED_B, PLATFORM_XBOX_SERIES, REGION_EUROPE, "2026-08-06")
                        .status("delayed");
        Fixture delayedFuture =
                day(PRESENTED_C, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-08-20")
                        .status("delayed")
                        .game(GAME_BANANZA);
        Fixture openMonth =
                day(PRESENTED_D, PLATFORM_WINDOWS_PC, REGION_WORLDWIDE, "2026-08-01")
                        .month("2026-08")
                        .game(GAME_BANANZA);
        insert(happened, delayedPast, delayedFuture, openMonth);
        try {
            var exact =
                    adapter.findPublishedReleases(
                                    range(
                                            LocalDate.of(2026, 8, 1),
                                            LocalDate.of(2026, 9, 30),
                                            false))
                            .orElseThrow();
            // A delayed day that has passed never happened; one still to come is expected.
            assertThat(exact.items())
                    .extracting(Item::gameId)
                    .containsExactly(GAME_DEATH_STRANDING, GAME_BANANZA, GAME_WOLVERINE);
            assertThat(presentedIds(exact, GAME_DEATH_STRANDING))
                    .containsExactly(happened.releaseId());
            assertThat(presentedIds(exact, GAME_BANANZA))
                    .containsExactly(delayedFuture.releaseId());

            // The month that has not ended joins from today on, without moving its game.
            var approximate =
                    adapter.findPublishedReleases(
                                    range(
                                            LocalDate.of(2026, 8, 1),
                                            LocalDate.of(2026, 9, 30),
                                            true))
                            .orElseThrow();
            assertThat(approximate.items())
                    .extracting(Item::gameId)
                    .containsExactly(GAME_DEATH_STRANDING, GAME_BANANZA, GAME_WOLVERINE);
            assertThat(presentedIds(approximate, GAME_BANANZA))
                    .containsExactly(delayedFuture.releaseId(), openMonth.releaseId());
        } finally {
            delete(happened, delayedPast, delayedFuture, openMonth);
        }
    }

    @Test
    void readsASingleDayAndTheFirstAndLastDaysOfAMonth() {
        Fixture firstOfMonth =
                day(PRESENTED_A, PLATFORM_PLAYSTATION_5, REGION_EUROPE, "2026-09-01");
        Fixture lastOfPrevious =
                day(PRESENTED_B, PLATFORM_PLAYSTATION_5, REGION_EUROPE, "2026-08-31")
                        .game(GAME_BANANZA);
        insert(firstOfMonth, lastOfPrevious);
        try {
            assertThat(
                            titles(
                                    adapter.findPublishedReleases(
                                                    range(
                                                            LocalDate.of(2026, 9, 25),
                                                            LocalDate.of(2026, 9, 25),
                                                            false))
                                            .orElseThrow()))
                    .containsExactly("Marvel's Wolverine");
            assertThat(
                            titles(
                                    adapter.findPublishedReleases(
                                                    range(
                                                            LocalDate.of(2026, 9, 1),
                                                            LocalDate.of(2026, 9, 30),
                                                            false))
                                            .orElseThrow()))
                    .containsExactly("Death Stranding 2: On the Beach", "Marvel's Wolverine");
            assertThat(
                            titles(
                                    adapter.findPublishedReleases(
                                                    range(
                                                            LocalDate.of(2026, 8, 31),
                                                            LocalDate.of(2026, 8, 31),
                                                            false))
                                            .orElseThrow()))
                    .containsExactly("Donkey Kong Bananza");
        } finally {
            delete(firstOfMonth, lastOfPrevious);
        }
    }

    @Test
    void crossesAYearWithTheApproximatePeriodsOfBothYears() {
        var range = range(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3), true);

        var result = adapter.findPublishedReleases(range).orElseThrow();

        // Quarter 4 of 2026 overlaps the old year's days; quarter 1 and the year 2027 the new
        // year's. None of them receives a day.
        assertThat(titles(result)).containsExactly("Subnautica 2", "Fable", "The Witcher IV");
        assertThat(presentedIds(result, GAME_SUBNAUTICA))
                .containsExactly(
                        "40000000-0000-4000-8000-000000000012",
                        "40000000-0000-4000-8000-000000000014");
        assertThat(
                        adapter.findPublishedReleases(
                                        range(
                                                LocalDate.of(2026, 12, 28),
                                                LocalDate.of(2027, 1, 3),
                                                false))
                                .orElseThrow()
                                .items())
                .isEmpty();
    }

    @Test
    void derivesFacetsFromTheSamePrecisionChoiceAsTheResults() {
        var exact = adapter.findPublishedReleases(criteria(FUTURE, 1, 20)).orElseThrow();
        // Only exact-day releases offer a facet value by default, so no option leads to a result
        // that the default range would hide.
        assertThat(exact.platforms())
                .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                .containsExactly(PLATFORM_PLAYSTATION_5);
        assertThat(exact.regions())
                .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                .containsExactly(REGION_WORLDWIDE);

        var approximate =
                adapter.findPublishedReleases(criteria(FUTURE, 1, 20, null, null, true))
                        .orElseThrow();
        assertThat(approximate.platforms())
                .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                .contains(PLATFORM_PLAYSTATION_5, PLATFORM_WINDOWS_PC, PLATFORM_XBOX_SERIES);
        // The unconfirmed region only holds a TBA release, which never belongs to a range.
        assertThat(approximate.regions())
                .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                .contains(REGION_WORLDWIDE, REGION_EUROPE)
                .doesNotContain(REGION_UNKNOWN);

        // A selected platform with only approximate releases stays representable to remove.
        var selected =
                adapter.findPublishedReleases(
                                criteria(FUTURE, 1, 20, PLATFORM_XBOX_SERIES, null, false))
                        .orElseThrow();
        assertThat(selected.items()).isEmpty();
        assertThat(selected.platforms())
                .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                .contains(PLATFORM_XBOX_SERIES);
    }

    @Test
    void countsOrdersAndPagesOnlyExactDayGamesByDefaultInPostgreSql() {
        String deathStrandingDay = "50000000-0000-4000-8000-000000000040";
        String bananzaMonth = "50000000-0000-4000-8000-000000000041";
        String bananzaDay = "50000000-0000-4000-8000-000000000042";
        insertUpcomingRelease(
                deathStrandingDay, GAME_DEATH_STRANDING, REGION_EUROPE, "day", "2026-08-20", null);
        insertUpcomingRelease(
                bananzaMonth, GAME_BANANZA, REGION_EUROPE, "month", null, YearMonth.of(2026, 8));
        insertUpcomingRelease(
                bananzaDay, GAME_BANANZA, REGION_WORLDWIDE, "day", "2026-09-01", null);
        try {
            var firstPage = adapter.findPublishedReleases(futurePage(1, 2, false)).orElseThrow();
            var secondPage = adapter.findPublishedReleases(futurePage(2, 2, false)).orElseThrow();

            // Three games have an exact day still to come; the count, order and pages ignore every
            // approximate release, including the month of a game that also has a day.
            assertThat(firstPage.totalItems()).isEqualTo(3);
            assertThat(firstPage.items())
                    .extracting(Item::gameId)
                    .containsExactly(GAME_DEATH_STRANDING, GAME_BANANZA);
            assertThat(firstPage.items().getLast().releases())
                    .extracting(ReleaseRow::releaseId)
                    .containsExactly(bananzaDay);
            assertThat(titles(secondPage)).containsExactly("Marvel's Wolverine");
            assertThat(secondPage.totalItems()).isEqualTo(3);

            // Opted in, the game presents its exact day for PlayStation 5 rather than its month on
            // the same platform, and games with only approximate dates follow in date order.
            var approximate = adapter.findPublishedReleases(futurePage(1, 20, true)).orElseThrow();
            assertThat(approximate.totalItems()).isEqualTo(7);
            assertThat(approximate.items())
                    .extracting(Item::gameId)
                    .startsWith(GAME_DEATH_STRANDING, GAME_BANANZA);
            assertThat(approximate.items().get(1).releases())
                    .extracting(ReleaseRow::releaseId)
                    .containsExactly(bananzaDay);
            // The month stays stored and is presented once a region filter selects only it.
            var europe =
                    adapter.findPublishedReleases(
                                    criteria(FUTURE, 1, 20, null, REGION_EUROPE, true))
                            .orElseThrow();
            assertThat(presentedIds(europe, GAME_BANANZA)).containsExactly(bananzaMonth);
            assertThat(titles(approximate).subList(2, 7))
                    .containsExactly(
                            "Marvel's Wolverine",
                            "Crimson Desert",
                            "Subnautica 2",
                            "Fable",
                            "The Witcher IV");
        } finally {
            for (String id : List.of(deathStrandingDay, bananzaMonth, bananzaDay)) {
                jdbcTemplate.update(
                        "DELETE FROM catalogue.release_snapshot WHERE release_id = ?::uuid", id);
                jdbcTemplate.update(
                        "DELETE FROM catalogue.game_release WHERE release_id = ?::uuid", id);
            }
        }
    }

    @Test
    void ordersByHypeHighestFirstWithMissingHypesLastInDateOrder() {
        var result = adapter.findPublishedReleases(sorted(HYPE, 1, 20)).orElseThrow();

        // The Witcher IV 93, Marvel's Wolverine 61, Crimson Desert 42, then the games without
        // Hypes by release date; a missing signal never counts as zero.
        assertThat(titles(result))
                .containsExactly(
                        "The Witcher IV",
                        "Marvel's Wolverine",
                        "Crimson Desert",
                        "Subnautica 2",
                        "Fable");

        // Equal Hypes continue with the release-date order before the game id.
        insertHypes(GAME_FABLE, 61);
        try {
            assertThat(titles(adapter.findPublishedReleases(sorted(HYPE, 1, 20)).orElseThrow()))
                    .containsExactly(
                            "The Witcher IV",
                            "Marvel's Wolverine",
                            "Fable",
                            "Crimson Desert",
                            "Subnautica 2");
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM catalogue.game_featured_evidence WHERE game_id = ?::uuid",
                    GAME_FABLE);
        }
    }

    @Test
    void ordersByCommunityRatingHighestFirstWithUnratedGamesLastInDateOrder() {
        insertCommunityScore(GAME_FABLE, "8.5", 3);
        insertCommunityScore(GAME_SUBNAUTICA, "8.5", 10);
        insertCommunityScore(GAME_CRIMSON_DESERT, "6.0", 1);
        try {
            var result = adapter.findPublishedReleases(sorted(RATING, 1, 20)).orElseThrow();

            // Equal means put more ratings first; unrated games follow by release date.
            assertThat(titles(result))
                    .containsExactly(
                            "Subnautica 2",
                            "Fable",
                            "Crimson Desert",
                            "Marvel's Wolverine",
                            "The Witcher IV");
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM catalogue.game_community_score WHERE game_id IN (?::uuid, ?::uuid, ?::uuid)",
                    GAME_FABLE,
                    GAME_SUBNAUTICA,
                    GAME_CRIMSON_DESERT);
        }
    }

    @Test
    void ordersByExternalPlayerScoreAsShownWithUnscoredGamesLastInDateOrder() {
        // 87.00 and 87.49 both show 8.7, so the larger count leads even though the raw mean
        // differs; 91 shows 9.1. A check that found no score sorts with the games never checked.
        insertExternalScore(GAME_FABLE, "87.00", 12L);
        insertExternalScore(GAME_SUBNAUTICA, "87.49", 3L);
        insertExternalScore(GAME_CRIMSON_DESERT, "91", 1L);
        insertExternalScore(GAME_WOLVERINE, null, null);
        try {
            assertThat(
                            titles(
                                    adapter.findPublishedReleases(
                                                    sorted(EXTERNAL_PLAYER_SCORE, 1, 20))
                                            .orElseThrow()))
                    .containsExactly(
                            "Crimson Desert",
                            "Fable",
                            "Subnautica 2",
                            "Marvel's Wolverine",
                            "The Witcher IV");
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM catalogue.game_external_score WHERE game_id IN (?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                    GAME_FABLE,
                    GAME_SUBNAUTICA,
                    GAME_CRIMSON_DESERT,
                    GAME_WOLVERINE);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(BrowseReleasesUseCase.Sort.class)
    void pagesEveryOrderDeterministicallyBeforePagination(BrowseReleasesUseCase.Sort sort) {
        insertCommunityScore(GAME_FABLE, "7.0", 2);
        try {
            List<String> whole =
                    gameIds(adapter.findPublishedReleases(sorted(sort, 1, 20)).orElseThrow());
            List<String> paged =
                    Stream.iterate(1, page -> page + 1)
                            .limit(whole.size())
                            .flatMap(
                                    page ->
                                            gameIds(
                                                    adapter.findPublishedReleases(
                                                                    sorted(sort, page, 1))
                                                            .orElseThrow())
                                                    .stream())
                            .toList();
            assertThat(paged).isEqualTo(whole).doesNotHaveDuplicates().hasSize(5);
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM catalogue.game_community_score WHERE game_id = ?::uuid",
                    GAME_FABLE);
        }
    }

    @Test
    void ordersTiedReleasesOfOneGameByTheUniqueReleaseIdInsideItsGroup() {
        String firstRelease = "50000000-0000-4000-8000-000000000010";
        String secondRelease = "50000000-0000-4000-8000-000000000011";
        insertTiedRelease(firstRelease, "10000000-0000-4000-8000-000000000001");
        insertTiedRelease(secondRelease, "10000000-0000-4000-8000-000000000004");
        try {
            var result = adapter.findPublishedReleases(criteria(FUTURE, 1, 50)).orElseThrow();

            // Both tied releases belong to game 001, so they group into one game.
            Item deathStranding =
                    result.items().stream()
                            .filter(item -> item.gameId().equals(GAME_DEATH_STRANDING))
                            .findFirst()
                            .orElseThrow();
            List<String> ids =
                    deathStranding.releases().stream().map(ReleaseRow::releaseId).toList();
            assertThat(ids.indexOf(secondRelease)).isEqualTo(ids.indexOf(firstRelease) + 1);
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM catalogue.release_snapshot WHERE release_id IN (?::uuid, ?::uuid)",
                    firstRelease,
                    secondRelease);
            jdbcTemplate.update(
                    "DELETE FROM catalogue.game_release WHERE release_id IN (?::uuid, ?::uuid)",
                    firstRelease,
                    secondRelease);
        }
    }

    @Test
    void classifiesADelayedDayByTheEvaluationDateWithoutMutatingData() {
        // Nothing about the rows changes; only the trusted evaluation date moves across their day.
        Fixture delayed =
                day(PRESENTED_A, PLATFORM_PLAYSTATION_5, REGION_EUROPE, "2026-06-15")
                        .status("delayed");
        Fixture scheduled = day(PRESENTED_B, PLATFORM_XBOX_SERIES, REGION_EUROPE, "2026-06-15");
        insert(delayed, scheduled);
        try {
            LocalDate dayBefore = LocalDate.of(2026, 6, 14);
            assertThat(releaseIds(dayBefore)).contains(delayed.releaseId(), scheduled.releaseId());

            // Once its day is reached a delayed release has not happened, so it leaves the range,
            // while a release without negative evidence stays.
            LocalDate dayOf = LocalDate.of(2026, 6, 15);
            assertThat(releaseIds(dayOf))
                    .contains(scheduled.releaseId())
                    .doesNotContain(delayed.releaseId());
        } finally {
            delete(delayed, scheduled);
        }
    }

    @Test
    void countsAndPagesGamesAfterApplyingTheRangeInPostgreSql() {
        Fixture first = day(PRESENTED_A, PLATFORM_PLAYSTATION_5, REGION_EUROPE, "2026-08-10");
        Fixture second = day(PRESENTED_B, PLATFORM_XBOX_SERIES, REGION_EUROPE, "2026-08-12");
        insert(first, second);
        try {
            var criteria =
                    new ReleaseBrowseReadPort.Criteria(
                            new ReleaseBrowseReadPort.Window(
                                    LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)),
                            TODAY,
                            RELEASE_DATE,
                            null,
                            null,
                            new ReleaseBrowseReadPort.Pagination(1, 1, 0),
                            false,
                            25);
            var result = adapter.findPublishedReleases(criteria).orElseThrow();
            assertThat(result.totalItems()).isEqualTo(1);
            assertThat(result.items()).singleElement();
            assertThat(result.items().getFirst().releases())
                    .extracting(ReleaseRow::releaseId)
                    .containsExactly(first.releaseId(), second.releaseId());
        } finally {
            delete(first, second);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Part.class)
    void presentsFullReleaseBeforePreReleaseWithoutCrossingTheActiveRegion(Part part) {
        Fixture early =
                day(
                        PRESENTED_A,
                        PLATFORM_PLAYSTATION_5,
                        REGION_WORLDWIDE,
                        part == PAST ? "2026-08-01" : "2026-09-29");
        Fixture full =
                day(
                                PRESENTED_B,
                                PLATFORM_PLAYSTATION_5,
                                REGION_WORLDWIDE,
                                part == PAST ? "2026-08-10" : "2026-10-02")
                        .review("required");
        Fixture regional =
                day(
                        PRESENTED_C,
                        PLATFORM_PLAYSTATION_5,
                        REGION_EUROPE,
                        part == PAST ? "2026-08-05" : "2026-09-29");
        insert(early, full, regional);
        jdbcTemplate.update(
                "UPDATE catalogue.release_snapshot SET release_stage='early_access' WHERE release_id IN (?::uuid,?::uuid)",
                early.releaseId(),
                regional.releaseId());
        jdbcTemplate.update(
                "UPDATE catalogue.release_snapshot SET release_stage='full_release' WHERE release_id=?::uuid",
                full.releaseId());
        try {
            var result = adapter.findPublishedReleases(presentation(part)).orElseThrow();
            assertThat(presentedIds(result, GAME_DEATH_STRANDING))
                    .containsExactly(full.releaseId());
            var filtered =
                    adapter.findPublishedReleases(
                                    presentation(part, List.of(), List.of(REGION_EUROPE)))
                            .orElseThrow();
            assertThat(presentedIds(filtered, GAME_DEATH_STRANDING))
                    .containsExactly(regional.releaseId());
            jdbcTemplate.update(
                    "UPDATE catalogue.release_snapshot SET release_status='delayed' WHERE release_id=?::uuid",
                    full.releaseId());
            assertThat(
                            presentedIds(
                                    adapter.findPublishedReleases(presentation(part)).orElseThrow(),
                                    GAME_DEATH_STRANDING))
                    .containsExactly(early.releaseId());
        } finally {
            delete(early, full, regional);
        }
    }

    @Test
    void presentsOneReleasePerPlatformWhileEveryOtherReleaseStaysStored() {
        // Real catalogue shape: one platform with two Worldwide dates (advance access and
        // standard), a regional date, and a second platform.
        Fixture earlyWorldwide =
                day(PRESENTED_A, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-01");
        Fixture worldwide =
                day(PRESENTED_B, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-20");
        Fixture japan = day(PRESENTED_C, PLATFORM_PLAYSTATION_5, REGION_JAPAN, "2026-08-01");
        Fixture xbox = day(PRESENTED_D, PLATFORM_XBOX_SERIES, REGION_EUROPE, "2026-07-05");
        insert(earlyWorldwide, worldwide, japan, xbox);
        try {
            var all = adapter.findPublishedReleases(presentation(PAST)).orElseThrow();
            assertThat(all.totalItems()).isEqualTo(1);
            // The earliest Worldwide day is presented for PlayStation 5: the Japanese day is
            // narrower in scope, and the later Worldwide day comes after it in date order.
            assertThat(presentedIds(all, GAME_DEATH_STRANDING))
                    .containsExactly(earlyWorldwide.releaseId(), xbox.releaseId());
            // Faceted options still come from every matching release, presented or not.
            assertThat(all.regions())
                    .extracting(ReleaseBrowseReadPort.Taxonomy::id)
                    .contains(REGION_WORLDWIDE, REGION_JAPAN, REGION_EUROPE);

            // A region filter presents that region's own release for the platform.
            assertThat(
                            presentedIds(
                                    adapter.findPublishedReleases(
                                                    presentation(
                                                            PAST, List.of(), List.of(REGION_JAPAN)))
                                            .orElseThrow(),
                                    GAME_DEATH_STRANDING))
                    .containsExactly(japan.releaseId());
            assertThat(
                            presentedIds(
                                    adapter.findPublishedReleases(
                                                    presentation(
                                                            PAST,
                                                            List.of(PLATFORM_PLAYSTATION_5),
                                                            List.of(REGION_WORLDWIDE)))
                                            .orElseThrow(),
                                    GAME_DEATH_STRANDING))
                    .containsExactly(earlyWorldwide.releaseId());

            // Presentation never merges or deletes: every release stays stored unchanged.
            assertThat(
                            jdbcTemplate.queryForObject(
                                    "SELECT count(*) FROM catalogue.release_snapshot WHERE release_id IN (?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                                    Integer.class,
                                    earlyWorldwide.releaseId(),
                                    worldwide.releaseId(),
                                    japan.releaseId(),
                                    xbox.releaseId()))
                    .isEqualTo(4);
        } finally {
            delete(earlyWorldwide, worldwide, japan, xbox);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("presentationPrecedence")
    void presentsTheStrongestEvidenceBeforeTheDateOrder(
            String rule, Part part, Fixture weaker, Fixture stronger) {
        // Except in the final id case, the weaker release has the smaller release id, so falling
        // through to the id tie-breaker would present it; only the named rule presents the other.
        insert(weaker, stronger);
        try {
            var result = adapter.findPublishedReleases(presentation(part)).orElseThrow();
            assertThat(presentedIds(result, GAME_DEATH_STRANDING))
                    .containsExactly(stronger.releaseId());
        } finally {
            delete(weaker, stronger);
        }
    }

    @Test
    void positionsEachGameByItsPresentedReleaseRatherThanAHiddenOne() {
        // Death Stranding 2's earliest date is pending review, so its accepted later day is
        // presented and positions it after Bananza's day.
        Fixture accepted = day(PRESENTED_A, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-20");
        Fixture pendingReview =
                day(PRESENTED_B, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-05")
                        .review("required");
        Fixture bananza =
                day(PRESENTED_C, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-10")
                        .game(GAME_BANANZA);
        insert(accepted, pendingReview, bananza);
        try {
            var result = adapter.findPublishedReleases(presentation(PAST)).orElseThrow();
            assertThat(result.items())
                    .extracting(Item::gameId)
                    .containsExactly(GAME_BANANZA, GAME_DEATH_STRANDING);
            assertThat(presentedIds(result, GAME_DEATH_STRANDING))
                    .containsExactly(accepted.releaseId());
        } finally {
            delete(accepted, pendingReview, bananza);
        }
    }

    private static Stream<Arguments> presentationPrecedence() {
        return Stream.of(
                Arguments.of(
                        "a delayed release yields to one without a negative signal",
                        FUTURE,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-09-01")
                                .status("delayed"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-10-01")),
                Arguments.of(
                        "a release pending review yields to accepted evidence (future)",
                        FUTURE,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-09-01")
                                .review("required"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-10-01")),
                Arguments.of(
                        "a release pending review yields to accepted evidence (past)",
                        PAST,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-10")
                                .review("required"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-08-10")),
                Arguments.of(
                        "provider-only evidence yields to verified evidence",
                        FUTURE,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-09-01"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_EUROPE, "2026-10-01")
                                .verification("verified")),
                Arguments.of(
                        "an approximate month yields to an exact day (future)",
                        FUTURE,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-09-01")
                                .month("2026-09"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-10-01")),
                Arguments.of(
                        "an approximate month yields to an exact day (past)",
                        PAST,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-01")
                                .month("2026-07"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-10")),
                Arguments.of(
                        "a specific region yields to Worldwide",
                        FUTURE,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_JAPAN, "2026-09-01"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-10-01")),
                Arguments.of(
                        "the earliest date is presented (future)",
                        FUTURE,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-10-01"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-09-01")),
                Arguments.of(
                        "the earliest date is presented (past)",
                        PAST,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-08-10"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-07-10")),
                Arguments.of(
                        "the region order breaks a tie between specific regions",
                        FUTURE,
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_JAPAN, "2026-09-01"),
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_EUROPE, "2026-09-01")),
                // A provider record repeating a date, as in the observed catalogue: only the
                // unique release id remains, so here the stronger release has the smaller id.
                Arguments.of(
                        "the release id closes the order",
                        FUTURE,
                        day(LARGER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-09-01")
                                .status("released"),
                        day(SMALLER_ID, PLATFORM_PLAYSTATION_5, REGION_WORLDWIDE, "2026-09-01")));
    }

    private static List<ReleaseRow> flatten(ReleaseBrowseReadPort.Result result) {
        return result.items().stream().flatMap(item -> item.releases().stream()).toList();
    }

    private static List<String> titles(ReleaseBrowseReadPort.Result result) {
        return result.items().stream().map(Item::canonicalTitle).toList();
    }

    private static List<String> gameIds(ReleaseBrowseReadPort.Result result) {
        return result.items().stream().map(Item::gameId).toList();
    }

    private static ReleaseBrowseReadPort.Criteria multi(
            List<String> platformIds, List<String> regionIds) {
        return new ReleaseBrowseReadPort.Criteria(
                PAST.window(),
                TODAY,
                RELEASE_DATE,
                platformIds,
                regionIds,
                new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                false,
                25);
    }

    /** The released ids of a June range read on {@code evaluatedOn}. */
    private static List<String> releaseIds(LocalDate evaluatedOn) {
        var criteria =
                new ReleaseBrowseReadPort.Criteria(
                        new ReleaseBrowseReadPort.Window(
                                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
                        evaluatedOn,
                        RELEASE_DATE,
                        null,
                        null,
                        new ReleaseBrowseReadPort.Pagination(1, 100, 0),
                        false,
                        25);
        return adapter.findPublishedReleases(criteria).orElseThrow().items().stream()
                .flatMap(item -> item.releases().stream())
                .map(ReleaseRow::releaseId)
                .toList();
    }

    @Test
    void rejectsAnUnknownPersistedEnumAsNonRetryableInvalidData() {
        String releaseId = "40000000-0000-4000-8000-000000000006";
        adminJdbcTemplate.execute(
                "ALTER TABLE catalogue.release_snapshot DROP CONSTRAINT ck_release_snapshot_source_kind");
        adminJdbcTemplate.update(
                "UPDATE catalogue.release_snapshot SET source_kind = 'corrupt' WHERE release_id = ?::uuid",
                releaseId);
        try {
            assertThatThrownBy(() -> adapter.findPublishedReleases(criteria(PAST, 1, 20)))
                    .isInstanceOf(CatalogueDataInvalidException.class)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported source kind");
        } finally {
            adminJdbcTemplate.update(
                    "UPDATE catalogue.release_snapshot SET source_kind = 'product_curated' WHERE release_id = ?::uuid",
                    releaseId);
            adminJdbcTemplate.execute(
                    "ALTER TABLE catalogue.release_snapshot ADD CONSTRAINT ck_release_snapshot_source_kind CHECK (source_kind IN ('external_provider', 'product_curated', 'official_source'))");
        }
    }

    @Test
    void cancelsABlockedStatementWithinTheConfiguredReadTimeout() throws Exception {
        JdbcTemplate boundedJdbc = new JdbcTemplate(runtimeDataSource);
        boundedJdbc.setQueryTimeout(1);
        var boundedAdapter =
                new JdbcReleaseBrowseReadAdapter(
                        new NamedParameterJdbcTemplate(boundedJdbc),
                        readTransaction(runtimeDataSource, 1));

        try (Connection blocker = PostgreSqlTestDatabase.adminConnection(DATABASE_NAME);
                Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute(
                    "LOCK TABLE catalogue.catalogue_publication IN ACCESS EXCLUSIVE MODE");
            long startedAt = System.nanoTime();
            try {
                assertThatThrownBy(
                                () -> boundedAdapter.findPublishedReleases(criteria(PAST, 1, 20)))
                        .isInstanceOf(CatalogueReadException.class)
                        .rootCause()
                        .isInstanceOf(SQLException.class)
                        .satisfies(
                                exception ->
                                        assertThat(((SQLException) exception).getSQLState())
                                                .isEqualTo("57014"));
                assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                        .isLessThan(Duration.ofSeconds(5));
            } finally {
                blocker.rollback();
            }
        }
    }

    /**
     * The two halves of the seeded calendar around {@link #TODAY}: six months that have happened,
     * and six months still to come.
     */
    enum Part {
        PAST(LocalDate.of(2026, 2, 13), TODAY),
        FUTURE(TODAY.plusDays(1), LocalDate.of(2027, 2, 13));

        private final LocalDate from;
        private final LocalDate to;

        Part(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
        }

        ReleaseBrowseReadPort.Window window() {
            return new ReleaseBrowseReadPort.Window(from, to);
        }
    }

    private static ReleaseBrowseReadPort.Criteria criteria(Part part, int page, int pageSize) {
        return criteria(part, page, pageSize, null, null, false);
    }

    private static ReleaseBrowseReadPort.Criteria futurePage(
            int page, int pageSize, boolean includeApproximateDates) {
        return criteria(FUTURE, page, pageSize, null, null, includeApproximateDates);
    }

    private static ReleaseBrowseReadPort.Criteria criteria(
            Part part,
            int page,
            int pageSize,
            String platformId,
            String regionId,
            boolean includeApproximateDates) {
        return new ReleaseBrowseReadPort.Criteria(
                part.window(),
                TODAY,
                RELEASE_DATE,
                platformId == null ? List.of() : List.of(platformId),
                regionId == null ? List.of() : List.of(regionId),
                new ReleaseBrowseReadPort.Pagination(page, pageSize, (long) (page - 1) * pageSize),
                includeApproximateDates,
                25);
    }

    private static ReleaseBrowseReadPort.Criteria range(
            LocalDate from, LocalDate to, boolean includeApproximateDates) {
        return new ReleaseBrowseReadPort.Criteria(
                new ReleaseBrowseReadPort.Window(from, to),
                TODAY,
                RELEASE_DATE,
                List.of(),
                List.of(),
                new ReleaseBrowseReadPort.Pagination(1, 50, 0),
                includeApproximateDates,
                25);
    }

    /** The future half with approximate dates, ordered by {@code sort}. */
    private static ReleaseBrowseReadPort.Criteria sorted(
            BrowseReleasesUseCase.Sort sort, int page, int pageSize) {
        return new ReleaseBrowseReadPort.Criteria(
                FUTURE.window(),
                TODAY,
                sort,
                List.of(),
                List.of(),
                new ReleaseBrowseReadPort.Pagination(page, pageSize, (long) (page - 1) * pageSize),
                true,
                25);
    }

    private static Stream<Arguments> filterCombinations() {
        return Stream.of(
                Arguments.of(
                        PAST,
                        null,
                        null,
                        false,
                        List.of(
                                "Resident Evil Requiem",
                                "Subnautica 2",
                                "Metroid Prime 4: Beyond",
                                "Crimson Desert",
                                "Pragmata")),
                Arguments.of(
                        PAST,
                        PLATFORM_PLAYSTATION_5,
                        null,
                        false,
                        List.of("Resident Evil Requiem", "Pragmata")),
                Arguments.of(
                        PAST,
                        null,
                        REGION_WORLDWIDE,
                        false,
                        List.of("Resident Evil Requiem", "Crimson Desert", "Pragmata")),
                Arguments.of(
                        PAST,
                        PLATFORM_WINDOWS_PC,
                        REGION_WORLDWIDE,
                        false,
                        List.of("Resident Evil Requiem", "Crimson Desert", "Pragmata")),
                Arguments.of(PAST, PLATFORM_XBOX_SERIES, null, false, List.of("Subnautica 2")),
                Arguments.of(PAST, null, REGION_JAPAN, false, List.of("Metroid Prime 4: Beyond")),
                Arguments.of(PAST, PLATFORM_WINDOWS_PC, REGION_EUROPE, false, List.of()),
                Arguments.of(FUTURE, null, null, false, List.of("Marvel's Wolverine")),
                Arguments.of(
                        FUTURE,
                        PLATFORM_PLAYSTATION_5,
                        REGION_WORLDWIDE,
                        false,
                        List.of("Marvel's Wolverine")),
                Arguments.of(FUTURE, PLATFORM_PLAYSTATION_5, REGION_EUROPE, false, List.of()),
                Arguments.of(FUTURE, PLATFORM_XBOX_SERIES, null, false, List.of()),
                Arguments.of(FUTURE, null, REGION_UNKNOWN, false, List.of()),
                Arguments.of(
                        FUTURE,
                        null,
                        null,
                        true,
                        List.of(
                                "Marvel's Wolverine",
                                "Crimson Desert",
                                "Subnautica 2",
                                "Fable",
                                "The Witcher IV")),
                Arguments.of(
                        FUTURE,
                        PLATFORM_PLAYSTATION_5,
                        REGION_EUROPE,
                        true,
                        List.of("Crimson Desert", "The Witcher IV")),
                Arguments.of(
                        FUTURE,
                        PLATFORM_XBOX_SERIES,
                        null,
                        true,
                        List.of("Crimson Desert", "Fable")),
                // The unconfirmed region holds only a TBA release, which never belongs to a range.
                Arguments.of(FUTURE, null, REGION_UNKNOWN, true, List.of()),
                Arguments.of(FUTURE, PLATFORM_WINDOWS_PC, REGION_UNKNOWN, true, List.of()),
                Arguments.of(FUTURE, PLATFORM_PLAYSTATION_5, REGION_JAPAN, true, List.of()));
    }

    /**
     * A past range and an approximate-date future range that hold no seed release of Death
     * Stranding 2, so each presentation case controls every candidate release of that game.
     */
    private static ReleaseBrowseReadPort.Criteria presentation(Part part) {
        return presentation(part, List.of(), List.of());
    }

    private static ReleaseBrowseReadPort.Criteria presentation(
            Part part, List<String> platformIds, List<String> regionIds) {
        boolean past = part == PAST;
        return new ReleaseBrowseReadPort.Criteria(
                past
                        ? new ReleaseBrowseReadPort.Window(
                                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 12))
                        : FUTURE.window(),
                TODAY,
                RELEASE_DATE,
                platformIds,
                regionIds,
                new ReleaseBrowseReadPort.Pagination(1, 50, 0),
                !past,
                25);
    }

    private static void insertHypes(String gameId, long hypes) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_featured_evidence (game_id, hypes, first_release_date, eligible_product, source_name, observed_at) VALUES (?::uuid, ?, NULL, true, 'IGDB', now())",
                gameId,
                hypes);
    }

    private static void insertExternalScore(String gameId, String score, Long count) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_external_score (game_id, source_name, score_kind, score, score_count, source_url, observed_at, changed_at) VALUES (?::uuid, 'IGDB', 'player', ?::numeric, ?, ?, now(), now())",
                gameId,
                score,
                count,
                score == null ? null : "https://www.igdb.com/games/fixture-" + gameId.substring(30));
    }

    private static void insertCommunityScore(String gameId, String mean, int count) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_community_score (game_id, mean, rating_count) VALUES (?::uuid, ?::numeric, ?)",
                gameId,
                mean,
                count);
    }

    private static TransactionTemplate readTransaction(DataSource dataSource, int timeoutSeconds) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(timeoutSeconds);
        return transaction;
    }

    private static void insertUpcomingRelease(
            String releaseId,
            String gameId,
            String regionId,
            String precision,
            String exactDate,
            YearMonth month) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                releaseId,
                gameId);
        jdbcTemplate.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES ('00000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?, ?, 'announced', 'product_curated', 'precision test', 'release', now(), 'verified', 'not_required')",
                releaseId,
                gameId,
                PLATFORM_PLAYSTATION_5,
                regionId,
                precision,
                exactDate,
                month == null ? null : month.getYear(),
                month == null ? null : month.getMonthValue());
    }

    private static void insertTiedRelease(String releaseId, String platformId) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, '30000000-0000-4000-8000-000000000001', now())",
                releaseId);
        jdbcTemplate.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES ('00000000-0000-4000-8000-000000000001', ?::uuid, '30000000-0000-4000-8000-000000000001', ?::uuid, '20000000-0000-4000-8000-000000000002', 'day', DATE '2026-08-14', 'announced', 'product_curated', 'tie test', 'release', now(), 'verified', 'not_required')",
                releaseId,
                platformId);
    }

    /** The presented release ids of one game, or none when the game is not on the page. */
    private static List<String> presentedIds(ReleaseBrowseReadPort.Result result, String gameId) {
        return result.items().stream()
                .filter(item -> item.gameId().equals(gameId))
                .flatMap(item -> item.releases().stream())
                .map(ReleaseRow::releaseId)
                .toList();
    }

    private static Fixture day(String releaseId, String platformId, String regionId, String date) {
        return new Fixture(
                releaseId,
                GAME_DEATH_STRANDING,
                platformId,
                regionId,
                "day",
                date,
                "announced",
                "provider_only",
                "not_required");
    }

    private static void insert(Fixture... fixtures) {
        for (Fixture fixture : fixtures) {
            YearMonth month =
                    "month".equals(fixture.precision()) ? YearMonth.parse(fixture.value()) : null;
            jdbcTemplate.update(
                    "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                    fixture.releaseId(),
                    fixture.gameId());
            jdbcTemplate.update(
                    "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES ('00000000-0000-4000-8000-000000000001', ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?, ?, ?, 'external_provider', 'IGDB', 'release_date', now(), ?, ?)",
                    fixture.releaseId(),
                    fixture.gameId(),
                    fixture.platformId(),
                    fixture.regionId(),
                    fixture.precision(),
                    month == null ? fixture.value() : null,
                    month == null ? null : month.getYear(),
                    month == null ? null : month.getMonthValue(),
                    fixture.status(),
                    fixture.verification(),
                    fixture.review());
        }
    }

    private static void delete(Fixture... fixtures) {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    "DELETE FROM catalogue.release_snapshot WHERE release_id = ?::uuid",
                    fixture.releaseId());
            jdbcTemplate.update(
                    "DELETE FROM catalogue.game_release WHERE release_id = ?::uuid",
                    fixture.releaseId());
        }
    }

    /** One stored release of a presentation case; {@code value} is a day or a year-month. */
    private record Fixture(
            String releaseId,
            String gameId,
            String platformId,
            String regionId,
            String precision,
            String value,
            String status,
            String verification,
            String review) {

        Fixture status(String newStatus) {
            return new Fixture(
                    releaseId,
                    gameId,
                    platformId,
                    regionId,
                    precision,
                    value,
                    newStatus,
                    verification,
                    review);
        }

        Fixture verification(String newVerification) {
            return new Fixture(
                    releaseId,
                    gameId,
                    platformId,
                    regionId,
                    precision,
                    value,
                    status,
                    newVerification,
                    review);
        }

        Fixture review(String newReview) {
            return new Fixture(
                    releaseId,
                    gameId,
                    platformId,
                    regionId,
                    precision,
                    value,
                    status,
                    verification,
                    newReview);
        }

        Fixture month(String yearMonth) {
            return new Fixture(
                    releaseId,
                    gameId,
                    platformId,
                    regionId,
                    "month",
                    yearMonth,
                    status,
                    verification,
                    review);
        }

        Fixture game(String newGameId) {
            return new Fixture(
                    releaseId,
                    newGameId,
                    platformId,
                    regionId,
                    precision,
                    value,
                    status,
                    verification,
                    review);
        }
    }
}
