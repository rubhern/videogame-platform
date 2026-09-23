package com.videogameplatform.catalogue.adapter.persistence.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import com.videogameplatform.catalogue.domain.CatalogueSearchText;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Execution(ExecutionMode.SAME_THREAD)
class JdbcGameSearchReadAdapterIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("game_search_adapter");
    private static JdbcTemplate jdbcTemplate;
    private static final String BROAD_QUERY = "b";
    private static JdbcGameSearchReadAdapter adapter;

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
        JdbcTemplate admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        seedRankingCases(admin);
        seedReleaseSummaryCases(admin);
        DataSource runtimeDataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbcTemplate = new JdbcTemplate(runtimeDataSource);
        adapter =
                new JdbcGameSearchReadAdapter(
                        new NamedParameterJdbcTemplate(jdbcTemplate),
                        readTransaction(runtimeDataSource));
    }

    @Test
    void appliesTheSameNormalizationRuleInJavaAndInPostgreSql() {
        List<String> samples =
                List.of(
                        "Ghost of Yōtei",
                        "Marvel's Wolverine",
                        "  Resident   Evil 4: Réquiem!  ",
                        "Xbox Series X|S",
                        "The Witcher IV",
                        "ÉÑÖÎÜ",
                        "東京 2020",
                        "ΟΣ",
                        "𐐀𐐁",
                        "Ø");

        for (String sample : samples) {
            String stored =
                    jdbcTemplate.queryForObject(
                            "SELECT catalogue.normalize_search_text(?)", String.class, sample);
            assertThat(stored)
                    .as("PostgreSQL and Java must normalize %s identically", sample)
                    .isEqualTo(CatalogueSearchText.of(sample).normalized());
        }
    }

    @Test
    void matchesTheCanonicalTitleCaseAndDiacriticInsensitively() {
        var result = search("ghost of yotei", 1, 20);

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items().getFirst().canonicalTitle()).isEqualTo("Ghost of Yōtei");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GHOST OF YŌTEI", "ghost of yotei", "  Ghost   of   Yotei  "})
    void treatsEveryEquivalentSpellingOfAQueryAsTheSameSearch(String query) {
        assertThat(search(query, 1, 20).items())
                .singleElement()
                .satisfies(item -> assertThat(item.gameId()).isNotBlank());
    }

    @Test
    void matchesTheWholeQueryThroughAnApprovedAlias() {
        var result = search("the witcher 4", 1, 20);

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items().getFirst().canonicalTitle()).isEqualTo("The Witcher IV");
        assertThat(result.items().getFirst().matchedAlias()).isEqualTo("The Witcher 4");
    }

    @Test
    void requiresEveryQueryTokenToMatch() {
        assertThat(search("resident evil", 1, 20).totalItems()).isEqualTo(1);
        assertThat(search("resident pragmata", 1, 20).totalItems()).isZero();
    }

    @Test
    void matchesAPartialTokenOnlyAsAWordPrefixAndNeverAsAnInfix() {
        assertThat(search("wolv", 1, 20).totalItems()).isEqualTo(1);
        assertThat(search("olverine", 1, 20).totalItems()).isZero();
    }

    @Test
    void keepsSeveralMatchingGamesSeparateInsteadOfResolvingOne() {
        var result = search("2", 1, 20);

        assertThat(result.totalItems()).isGreaterThan(1);
        assertThat(result.items())
                .extracting(GameSearchReadPort.Item::gameId)
                .doesNotHaveDuplicates();
    }

    @Test
    void neverDuplicatesAGameThatSeveralApprovedAliasesMatch() {
        // Resident Evil Requiem carries both `Biohazard Requiem` and `Resident Evil 9`.
        var result = search("re", 1, 100);

        assertThat(result.items())
                .extracting(GameSearchReadPort.Item::gameId)
                .doesNotHaveDuplicates();
        assertThat(result.totalItems()).isEqualTo(result.items().size());
    }

    @Test
    void ignoresAnAliasThatIsNotApproved() {
        assertThat(search("samus", 1, 20).totalItems()).isZero();
    }

    @Test
    void returnsNoResultForASupportedLookingTitleOutsideTheBoundedCatalogue() {
        var result = search("elden ring", 1, 20);

        assertThat(result.totalItems()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(result.publicationVersion()).isEqualTo("prototype-catalogue-v1");
    }

    @Test
    void ranksExactCanonicalTitlesBeforePrefixesBeforePlainTokenMatchesAndAliasesLast() {
        var result = search("zeta", 1, 20);
        assertThat(result.totalItems()).isEqualTo(7);
        assertThat(result.items())
                .extracting(GameSearchReadPort.Item::canonicalTitle)
                .containsExactly(
                        "Zéta", "Zeta", "Zeta omega", "Omega zeta", "Alpha", "Delta", "Gamma");
        assertThat(result.items().get(5).matchedAlias()).isEqualTo("Zeta delta");
        assertThat(result.items()).allSatisfy(item -> assertThat(item.releaseContext()).isEmpty());
    }

    @Test
    void usesGameIdToBreakTiesAcrossPageBoundaries() {
        assertThat(search("zeta", 1, 1).items().getFirst().gameId()).isEqualTo(rankingGameId(1));
        assertThat(search("zeta", 2, 1).items().getFirst().gameId()).isEqualTo(rankingGameId(2));
    }

    private static String rankingGameId(int number) {
        return "94000000-0000-4000-8000-%012d".formatted(number);
    }

    private static void seedRankingCases(JdbcTemplate admin) {
        String publicationId =
                admin.queryForObject(
                        "SELECT publication_id::text FROM catalogue.catalogue_publication WHERE is_current",
                        String.class);
        List<String> titles =
                List.of("Zéta", "Zeta", "Zeta omega", "Omega zeta", "Alpha", "Delta", "Gamma");
        // Reverse insertion order makes the unique final sort key observable.
        for (int i = titles.size(); i >= 1; i--) {
            admin.update(
                    "INSERT INTO catalogue.game (game_id, created_at) VALUES (?::uuid, now())",
                    rankingGameId(i));
            admin.update(
                    """
                    INSERT INTO catalogue.game_snapshot
                        (publication_id, game_id, canonical_title, slug, cover_reference, cover_source,
                         cover_usage_mode, cover_alternative_text, cover_usage_status)
                    VALUES (?::uuid, ?::uuid, ?, ?, '/assets/covers/fallback.svg', 'VideoGame Platform',
                            'product_owned', 'Ranking fixture', 'approved')
                    """,
                    publicationId,
                    rankingGameId(i),
                    titles.get(i - 1),
                    "ranking-fixture-" + i);
        }
        for (var alias :
                List.of(
                        new String[] {"5", "Zeta"}, new String[] {"6", "Zeta epsilon"},
                        new String[] {"6", "Zeta delta"}, new String[] {"7", "Omega zeta"})) {
            admin.update(
                    """
                    INSERT INTO catalogue.game_alias
                        (publication_id, game_id, alias, alias_kind, approval_status, source_kind, source_name)
                    VALUES (?::uuid, ?::uuid, ?, 'product_curated', 'approved', 'product_curated', 'Ranking fixture')
                    """,
                    publicationId,
                    rankingGameId(Integer.parseInt(alias[0])),
                    alias[1]);
        }
    }

    @Test
    void ordersResultsDeterministicallyAndPagesWithoutOverlapOrGaps() {
        // A single-letter prefix deliberately spans several games and both match sources.
        var everything = search(BROAD_QUERY, 1, 100);
        var firstPage = search(BROAD_QUERY, 1, 2);
        var secondPage = search(BROAD_QUERY, 2, 2);

        assertThat(everything.totalItems()).isEqualTo(4);
        assertThat(firstPage.totalItems()).isEqualTo(everything.totalItems());
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.items().stream().map(GameSearchReadPort.Item::gameId).toList())
                .isEqualTo(
                        everything.items().subList(0, 2).stream()
                                .map(GameSearchReadPort.Item::gameId)
                                .toList());
        assertThat(secondPage.items().stream().map(GameSearchReadPort.Item::gameId).toList())
                .isEqualTo(
                        everything.items().subList(2, 4).stream()
                                .map(GameSearchReadPort.Item::gameId)
                                .toList());
    }

    @Test
    void repeatsTheSameOrderForTheSameQuery() {
        assertThat(
                        search(BROAD_QUERY, 1, 100).items().stream()
                                .map(GameSearchReadPort.Item::gameId)
                                .toList())
                .isEqualTo(
                        search(BROAD_QUERY, 1, 100).items().stream()
                                .map(GameSearchReadPort.Item::gameId)
                                .toList());
    }

    @Test
    void returnsAnEmptyPageBeyondTheLastOneWithoutLosingTheTotal() {
        var result = search(BROAD_QUERY, 50, 20);

        assertThat(result.totalItems()).isEqualTo(4);
        assertThat(result.items()).isEmpty();
    }

    @Test
    void boundsTheReleaseContextPerResultInPostgreSql() {
        var unbounded = search("crimson", 1, 20, 10);
        var bounded = search("crimson", 1, 20, 1);

        assertThat(unbounded.items().getFirst().releaseContext()).hasSizeGreaterThan(1);
        assertThat(bounded.items().getFirst().releaseContext()).hasSize(1);
        assertThat(bounded.items().getFirst().releaseContext().getFirst())
                .isEqualTo(unbounded.items().getFirst().releaseContext().getFirst());
    }

    @Test
    void readsTheCoverProvenanceNeededToApplyTheApprovedCoverPolicy() {
        var result = search("crimson desert", 1, 20);

        assertThat(result.items().getFirst().cover())
                .isInstanceOf(
                        com.videogameplatform.catalogue.application.cover.port
                                .CatalogueCoverReference.Unavailable.class);
    }

    @Test
    void summarizesEveryDistinctPlatformEvenWhenDuplicateEarlyReleasesFillTheContext() {
        var spread = searchOne("quokka spread", 3);

        assertThat(spread.releaseContext())
                .hasSize(3)
                .extracting(context -> context.platform().name())
                .containsOnly("PlayStation 5");
        assertThat(spread.releaseSummary().totalPlatforms()).isEqualTo(5);
        assertThat(spread.releaseSummary().platforms())
                .extracting(GameSearchReadPort.Taxonomy::name)
                .containsExactly("Nintendo Switch 2", "PlayStation 5", "Sega Saturn");
    }

    @Test
    void computesTheSummaryIndependentlyOfTheReleaseContextLimit() {
        assertThat(searchOne("quokka spread", 1).releaseSummary())
                .isEqualTo(searchOne("quokka spread", 10).releaseSummary());
    }

    @Test
    void spansTheKnownYearsOfEveryPrecisionAndStatusWithoutUnknownDates() {
        var summary = searchOne("quokka spread", 3).releaseSummary();

        assertThat(summary.earliestKnownYear()).isEqualTo(2019);
        assertThat(summary.latestKnownYear()).isEqualTo(2024);
    }

    @Test
    void reportsOneKnownYearAsBothEarliestAndLatest() {
        var summary = searchOne("quokka single", 3).releaseSummary();

        assertThat(summary.platforms())
                .extracting(GameSearchReadPort.Taxonomy::name)
                .containsExactly("PlayStation 5", "Windows PC");
        assertThat(summary.totalPlatforms()).isEqualTo(2);
        assertThat(summary.earliestKnownYear()).isEqualTo(2026);
        assertThat(summary.latestKnownYear()).isEqualTo(2026);
    }

    @Test
    void neverInventsAYearWhenEveryReleaseDateIsUnknown() {
        var summary = searchOne("quokka unknown", 3).releaseSummary();

        assertThat(summary.platforms())
                .extracting(GameSearchReadPort.Taxonomy::name)
                .containsExactly("Xbox Series X|S");
        assertThat(summary.totalPlatforms()).isEqualTo(1);
        assertThat(summary.earliestKnownYear()).isNull();
        assertThat(summary.latestKnownYear()).isNull();
    }

    @Test
    void summarizesAGameWithoutStoredReleasesAsEmpty() {
        var summary = searchOne("quokka empty", 3).releaseSummary();

        assertThat(summary.platforms()).isEmpty();
        assertThat(summary.totalPlatforms()).isZero();
        assertThat(summary.earliestKnownYear()).isNull();
    }

    @Test
    void leavesRankingCountAndPaginationUnchangedByTheSummary() {
        var everything = search("quokka", 1, 20);

        assertThat(everything.totalItems()).isEqualTo(4);
        assertThat(everything.items())
                .extracting(GameSearchReadPort.Item::canonicalTitle)
                .containsExactly(
                        "Quokka Empty", "Quokka Single", "Quokka Spread", "Quokka Unknown");
        for (int page = 1; page <= 4; page++) {
            var single = search("quokka", page, 1);
            assertThat(single.totalItems()).isEqualTo(4);
            assertThat(single.items()).singleElement().isEqualTo(everything.items().get(page - 1));
        }
    }

    private static GameSearchReadPort.Item searchOne(String text, int releaseContextLimit) {
        var result = search(text, 1, 20, releaseContextLimit);
        assertThat(result.totalItems()).isEqualTo(1);
        return result.items().getFirst();
    }

    private static String summaryGameId(int number) {
        return "95000000-0000-4000-8000-%012d".formatted(number);
    }

    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String SWITCH_2 = "10000000-0000-4000-8000-000000000002";
    private static final String WINDOWS = "10000000-0000-4000-8000-000000000003";
    private static final String XBOX = "10000000-0000-4000-8000-000000000004";
    private static final String SATURN = "97000000-0000-4000-8000-000000000001";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String EUROPE = "20000000-0000-4000-8000-000000000002";
    private static final String UNKNOWN_REGION = "20000000-0000-4000-8000-000000000003";

    /**
     * Quokka Spread fills a context of three with early PlayStation 5 releases while four more
     * platforms, a cancelled latest year and an unknown date lie beyond it.
     */
    private static void seedReleaseSummaryCases(JdbcTemplate admin) {
        String publicationId =
                admin.queryForObject(
                        "SELECT publication_id::text FROM catalogue.catalogue_publication WHERE is_current",
                        String.class);
        admin.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name)"
                        + " VALUES (?::uuid, 'summary-fixture-saturn', 'Sega Saturn')",
                SATURN);
        List<String> titles =
                List.of("Quokka Spread", "Quokka Single", "Quokka Unknown", "Quokka Empty");
        for (int i = 1; i <= titles.size(); i++) {
            admin.update(
                    "INSERT INTO catalogue.game (game_id, created_at) VALUES (?::uuid, now())",
                    summaryGameId(i));
            admin.update(
                    """
                    INSERT INTO catalogue.game_snapshot
                        (publication_id, game_id, canonical_title, slug, cover_reference, cover_source,
                         cover_usage_mode, cover_alternative_text, cover_usage_status)
                    VALUES (?::uuid, ?::uuid, ?, ?, '/assets/covers/fallback.svg', 'VideoGame Platform',
                            'product_owned', 'Summary fixture', 'approved')
                    """,
                    publicationId,
                    summaryGameId(i),
                    titles.get(i - 1),
                    "summary-fixture-" + i);
        }
        List<Object[]> releases =
                List.of(
                        new Object[] {
                            1, PS5, EUROPE, "day", "2019-03-01", null, null, null, "announced"
                        },
                        new Object[] {1, PS5, WORLDWIDE, "month", null, 2019, 5, null, "announced"},
                        new Object[] {
                            1, PS5, UNKNOWN_REGION, "quarter", null, 2020, null, 1, "announced"
                        },
                        new Object[] {
                            1, WINDOWS, WORLDWIDE, "year", null, 2021, null, null, "announced"
                        },
                        new Object[] {
                            1, XBOX, EUROPE, "day", "2022-11-10", null, null, null, "delayed"
                        },
                        new Object[] {
                            1, SWITCH_2, WORLDWIDE, "unknown", null, null, null, null, "announced"
                        },
                        new Object[] {
                            1, SATURN, EUROPE, "year", null, 2024, null, null, "cancelled"
                        },
                        new Object[] {
                            2, PS5, EUROPE, "day", "2026-01-15", null, null, null, "announced"
                        },
                        new Object[] {
                            2, WINDOWS, WORLDWIDE, "quarter", null, 2026, null, 4, "announced"
                        },
                        new Object[] {
                            2, WINDOWS, EUROPE, "month", null, 2026, 7, null, "announced"
                        },
                        new Object[] {
                            3, XBOX, EUROPE, "unknown", null, null, null, null, "announced"
                        },
                        new Object[] {
                            3, XBOX, WORLDWIDE, "unknown", null, null, null, null, "announced"
                        });
        for (int i = 0; i < releases.size(); i++) {
            Object[] release = releases.get(i);
            String releaseId = "96000000-0000-4000-8000-%012d".formatted(i + 1);
            String gameId = summaryGameId((Integer) release[0]);
            admin.update(
                    "INSERT INTO catalogue.game_release (release_id, game_id, created_at)"
                            + " VALUES (?::uuid, ?::uuid, now())",
                    releaseId,
                    gameId);
            admin.update(
                    """
                    INSERT INTO catalogue.release_snapshot
                        (publication_id, release_id, game_id, platform_id, region_id, date_precision,
                         exact_date, release_year, release_month, release_quarter, release_status,
                         source_kind, source_name, source_entity_type, last_synchronized_at,
                         verification_level, review_status)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?, ?, ?, ?,
                            'product_curated', 'Summary fixture', 'fixture_release', now(),
                            'provider_only', 'not_required')
                    """,
                    publicationId,
                    releaseId,
                    gameId,
                    release[1],
                    release[2],
                    release[3],
                    release[4],
                    release[5],
                    release[6],
                    release[7],
                    release[8]);
        }
    }

    private static GameSearchReadPort.Result search(String text, int page, int pageSize) {
        return search(text, page, pageSize, 3);
    }

    private static GameSearchReadPort.Result search(
            String text, int page, int pageSize, int releaseContextLimit) {
        CatalogueSearchText searchText = CatalogueSearchText.of(text);
        return adapter.findMatchingGames(
                        new GameSearchReadPort.Criteria(
                                searchText.normalized(),
                                searchText.tokens(),
                                new GameSearchReadPort.Pagination(
                                        page, pageSize, (long) (page - 1) * pageSize),
                                releaseContextLimit,
                                3))
                .orElseThrow();
    }

    private static TransactionTemplate readTransaction(DataSource dataSource) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
        return transaction;
    }
}
