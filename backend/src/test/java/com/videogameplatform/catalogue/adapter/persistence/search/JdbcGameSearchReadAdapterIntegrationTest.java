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
        seedRankingCases(
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword())));
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
                                releaseContextLimit))
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
