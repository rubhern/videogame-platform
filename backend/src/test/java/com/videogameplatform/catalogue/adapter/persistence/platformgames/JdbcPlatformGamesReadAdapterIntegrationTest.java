package com.videogameplatform.catalogue.adapter.persistence.platformgames;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.ReleaseState;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.Sort;
import com.videogameplatform.catalogue.application.platformgames.PlatformGames.Taxonomy;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort.Criteria;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort.Item;
import com.videogameplatform.catalogue.domain.CatalogueSearchText;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * UC-019 against real PostgreSQL: one game per scope whatever its releases, the presented release,
 * families, release states, orders with ties and unknown dates, facets, search and paging.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JdbcPlatformGamesReadAdapterIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("platform_games_adapter");
    private static final String PUBLICATION = "00000000-0000-4000-8000-0000000000bb";
    private static final String SWITCH = "10000000-0000-4000-8000-0000000000b1";
    private static final String SWITCH_VARIANT = "10000000-0000-4000-8000-0000000000b2";
    private static final String OTHER = "10000000-0000-4000-8000-0000000000b3";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-0000000000b1";
    private static final String EUROPE = "20000000-0000-4000-8000-0000000000b2";
    private static final String JAPAN = "20000000-0000-4000-8000-0000000000b3";
    private static final String ADVENTURE = "50000000-0000-4000-8000-0000000000b1";
    private static final String PUZZLE = "50000000-0000-4000-8000-0000000000b2";
    private static final String RACING = "50000000-0000-4000-8000-0000000000b3";
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    /** Every game of the {@code SWITCH} scope, latest presented date first. */
    private static final List<String> LATEST_FIRST =
            List.of(
                    "Gamma Quarter And Month",
                    "Beta Upcoming Day",
                    // A delayed release keeps its own date; it is still to come.
                    "Iota Delayed",
                    "Epsilon Out And Coming",
                    "Lambda Year",
                    "Alpha Released Multi",
                    "Kappa",
                    // Two games on one day follow their normalized titles.
                    "Tie A",
                    "Tie B",
                    // An unknown date follows every dated game.
                    "Delta Undated");

    private static JdbcTemplate admin;
    private static JdbcPlatformGamesReadAdapter adapter;
    private static int releaseSequence;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        PostgreSqlTestDatabase.createDatabase(DATABASE_NAME);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        DataSource runtime =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        adapter =
                new JdbcPlatformGamesReadAdapter(
                        new NamedParameterJdbcTemplate(runtime), readTransaction(runtime));
        seed();
    }

    @Test
    void browsesEveryGameOfTheScopeOnceWithoutAQuery() {
        var result = read(criteria().build());

        assertThat(result.publicationVersion()).isEqualTo("platform-games-fixture");
        assertThat(result.platforms()).containsExactly(new Taxonomy(SWITCH, "Switch Two"));
        assertThat(titles(result)).containsExactlyElementsOf(LATEST_FIRST);
        assertThat(result.totalItems()).isEqualTo(LATEST_FIRST.size());
        // Only cancelled on the scope, or only on another platform: never associated.
        assertThat(titles(result)).doesNotContain("Zeta Cancelled Here", "Eta Elsewhere");
    }

    @Test
    void presentsAGameThatIsOutByItsReleaseThatIsOut() {
        var items = read(criteria().build()).items();

        Item multi = item(items, "Alpha Released Multi");
        // Two regions on the scope and an older release elsewhere: one row, the earliest of the
        // scope, never the other platform's.
        assertThat(multi.platform()).isEqualTo(new Taxonomy(SWITCH, "Switch Two"));
        assertThat(multi.region().name()).isEqualTo("Japón");
        assertThat(multi.releaseDate()).isEqualTo(new ReleaseDate.Day(LocalDate.of(2024, 12, 1)));

        // Released in Japan, still to come in Europe: it is out, presented by its release.
        Item outAndComing = item(items, "Epsilon Out And Coming");
        assertThat(outAndComing.releaseDate())
                .isEqualTo(new ReleaseDate.Day(LocalDate.of(2026, 1, 10)));

        // Expected: the most precise expected date, month before quarter, never a day.
        Item quarterAndMonth = item(items, "Gamma Quarter And Month");
        assertThat(quarterAndMonth.releaseDate())
                .isEqualTo(new ReleaseDate.Month(java.time.YearMonth.of(2026, 9)));

        assertThat(item(items, "Delta Undated").releaseDate()).isEqualTo(new ReleaseDate.Unknown());
        assertThat(item(items, "Iota Delayed").status()).isEqualTo(ReleaseStatus.DELAYED);
    }

    @Test
    void readsAPlatformFamilyAsTheUnionOfItsPlatforms() {
        var family = read(criteria().platforms(SWITCH, SWITCH_VARIANT).build());

        assertThat(family.platforms())
                .containsExactly(
                        new Taxonomy(SWITCH, "Switch Two"),
                        new Taxonomy(SWITCH_VARIANT, "Switch Two Lite"));
        assertThat(titles(family)).contains("Theta Variant Only").doesNotHaveDuplicates();
        assertThat(family.totalItems()).isEqualTo(LATEST_FIRST.size() + 1);
        assertThat(titles(read(criteria().build()))).doesNotContain("Theta Variant Only");
    }

    @Test
    void splitsTheScopeIntoReleasedAndUpcomingGamesByTheEvaluationDate() {
        var released = titles(read(criteria().state(ReleaseState.RELEASED).build()));
        var upcoming = titles(read(criteria().state(ReleaseState.UPCOMING).build()));

        assertThat(released)
                .containsExactly(
                        "Epsilon Out And Coming",
                        "Lambda Year",
                        "Alpha Released Multi",
                        "Kappa",
                        "Tie A",
                        "Tie B");
        assertThat(upcoming)
                .containsExactly(
                        "Gamma Quarter And Month",
                        "Beta Upcoming Day",
                        "Iota Delayed",
                        "Delta Undated");
        Set<String> union = new HashSet<>(released);
        union.addAll(upcoming);
        assertThat(union).hasSize(LATEST_FIRST.size());
    }

    @Test
    void ordersByEveryOrderWithUnknownDatesLastAndTheGameAsLastKey() {
        assertThat(titles(read(criteria().sort(Sort.RELEASE_DATE_ASC).build())))
                .containsExactly(
                        "Tie A",
                        "Tie B",
                        "Kappa",
                        "Alpha Released Multi",
                        "Lambda Year",
                        "Epsilon Out And Coming",
                        "Iota Delayed",
                        "Beta Upcoming Day",
                        "Gamma Quarter And Month",
                        "Delta Undated");
        List<String> byTitle = titles(read(criteria().sort(Sort.TITLE_ASC).build()));
        assertThat(byTitle.getFirst()).isEqualTo("Alpha Released Multi");
        assertThat(titles(read(criteria().sort(Sort.TITLE_DESC).build())))
                .containsExactlyElementsOf(byTitle.reversed());
        // Only two games hold a player score; every other game follows, never as zero.
        assertThat(titles(read(criteria().sort(Sort.EXTERNAL_PLAYER_SCORE).build())))
                .startsWith("Tie B", "Kappa", "Gamma Quarter And Month");
        assertThat(titles(read(criteria().sort(Sort.COMMUNITY_SCORE).build())))
                .startsWith("Lambda Year", "Gamma Quarter And Month");
    }

    @Test
    void filtersByTheKnownYearOfThePresentedReleaseAndFacetsTheYearsWithoutIt() {
        var result = read(criteria().years(2025, 2026).build());

        assertThat(titles(result))
                .containsExactly(
                        "Gamma Quarter And Month",
                        "Beta Upcoming Day",
                        "Iota Delayed",
                        "Epsilon Out And Coming",
                        "Lambda Year");
        assertThat(result.earliestYear()).isEqualTo(2022);
        assertThat(result.latestYear()).isEqualTo(2026);
        assertThat(titles(read(criteria().years(2026, null).build()))).hasSize(4);
    }

    @Test
    void filtersByGenresWithOrAndFacetsTheGenresOfTheOtherFilters() {
        var result = read(criteria().genres("adventure", "racing").build());

        assertThat(titles(result))
                .containsExactly("Beta Upcoming Day", "Alpha Released Multi", "Kappa");
        // The facet ignores the genre selection; a selected genre without games stays.
        assertThat(result.genres())
                .extracting(GameDetailsResult.Term::code)
                .containsExactly("adventure", "racing", "puzzle");
        assertThat(result.knownGenreIds()).containsExactly("adventure", "racing");

        var released = read(criteria().state(ReleaseState.RELEASED).build());
        assertThat(released.genres())
                .extracting(GameDetailsResult.Term::code)
                .containsExactly("adventure", "puzzle");
    }

    @Test
    void searchesTitlesAndApprovedAliasesInsideTheScopeOnly() {
        var byTitle = read(criteria().search("tie").build());
        assertThat(titles(byTitle)).containsExactly("Tie A", "Tie B");
        assertThat(byTitle.items()).extracting(Item::matchedAlias).containsOnlyNulls();

        var byAlias = read(criteria().search("hidden").build());
        assertThat(titles(byAlias)).containsExactly("Kappa");
        assertThat(byAlias.items().getFirst().matchedAlias()).isEqualTo("Hidden Name");

        assertThat(read(criteria().search("pending").build()).items()).isEmpty();
        // Matches elsewhere never leak into the scope.
        assertThat(read(criteria().search("eta").build()).items()).isEmpty();
    }

    @Test
    void pagesOneStableSequenceWithoutGapsOrRepeats() {
        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            var result = read(criteria().page(page, 3).build());
            assertThat(result.totalItems()).isEqualTo(LATEST_FIRST.size());
            paged.addAll(titles(result));
        }
        var beyond = read(criteria().page(9, 3).build());

        assertThat(paged).containsExactlyElementsOf(LATEST_FIRST);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalItems()).isEqualTo(LATEST_FIRST.size());
    }

    @Test
    void readsNoGameForAnUnknownPlatformOrGenre() {
        var unknownPlatform =
                read(criteria().platforms(SWITCH, "10000000-0000-4000-8000-0000000000ff").build());
        assertThat(unknownPlatform.platforms()).hasSize(1);
        assertThat(unknownPlatform.items()).isEmpty();

        var notAUuid = read(criteria().platforms("platform_switch").build());
        assertThat(notAUuid.platforms()).isEmpty();

        var unknownGenre = read(criteria().genres("adventure", "invented").build());
        assertThat(unknownGenre.knownGenreIds()).containsExactly("adventure");
        assertThat(unknownGenre.items()).isEmpty();
    }

    @Test
    void readsNothingWhenNoPublicationIsCurrent() {
        admin.update("UPDATE catalogue.catalogue_publication SET is_current = false");
        try {
            assertThat(adapter.findPlatformGames(criteria().build())).isEmpty();
        } finally {
            admin.update(
                    "UPDATE catalogue.catalogue_publication SET is_current = true WHERE publication_id = ?::uuid",
                    PUBLICATION);
        }
    }

    private static PlatformGamesReadPort.Result read(Criteria criteria) {
        return adapter.findPlatformGames(criteria).orElseThrow();
    }

    private static List<String> titles(PlatformGamesReadPort.Result result) {
        return result.items().stream().map(Item::canonicalTitle).toList();
    }

    private static Item item(List<Item> items, String title) {
        return items.stream()
                .filter(item -> item.canonicalTitle().equals(title))
                .findFirst()
                .orElseThrow();
    }

    private static CriteriaBuilder criteria() {
        return new CriteriaBuilder();
    }

    private static final class CriteriaBuilder {
        private List<String> platforms = List.of(SWITCH);
        private PlatformGamesReadPort.Search search;
        private ReleaseState state;
        private Integer fromYear;
        private Integer toYear;
        private List<String> genres = List.of();
        private Sort sort = Sort.RELEASE_DATE_DESC;
        private int page = 1;
        private int size = 100;

        CriteriaBuilder platforms(String... ids) {
            platforms = List.of(ids);
            return this;
        }

        CriteriaBuilder search(String text) {
            CatalogueSearchText normalized = CatalogueSearchText.of(text);
            search = new PlatformGamesReadPort.Search(normalized.normalized(), normalized.tokens());
            return this;
        }

        CriteriaBuilder state(ReleaseState value) {
            state = value;
            return this;
        }

        CriteriaBuilder years(Integer from, Integer to) {
            fromYear = from;
            toYear = to;
            return this;
        }

        CriteriaBuilder genres(String... codes) {
            genres = List.of(codes);
            return this;
        }

        CriteriaBuilder sort(Sort value) {
            sort = value;
            return this;
        }

        CriteriaBuilder page(int number, int pageSize) {
            page = number;
            size = pageSize;
            return this;
        }

        Criteria build() {
            return new Criteria(
                    platforms,
                    search,
                    state,
                    fromYear,
                    toYear,
                    genres,
                    sort,
                    TODAY,
                    new PlatformGamesReadPort.Pagination(page, size, (long) (page - 1) * size));
        }
    }

    private static void seed() {
        admin.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) VALUES (?::uuid, 'platform-games-fixture', now(), now(), 'product_curated', 'platform games fixture', true)",
                PUBLICATION);
        admin.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES (?::uuid, 'pg-switch', 'Switch Two'), (?::uuid, 'pg-switch-lite', 'Switch Two Lite'), (?::uuid, 'pg-other', 'Other Box')",
                SWITCH,
                SWITCH_VARIANT,
                OTHER);
        admin.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES (?::uuid, 'pg-worldwide', 'Mundial'), (?::uuid, 'pg-europe', 'Europa'), (?::uuid, 'pg-japan', 'Japón')",
                WORLDWIDE,
                EUROPE,
                JAPAN);
        admin.update(
                "INSERT INTO catalogue.genre (genre_id, code, display_name) VALUES (?::uuid, 'adventure', 'Aventura'), (?::uuid, 'puzzle', 'Puzles'), (?::uuid, 'racing', 'Carreras')",
                ADVENTURE,
                PUZZLE,
                RACING);

        String alpha = game(1, "Alpha Released Multi");
        release(alpha, SWITCH, EUROPE, "day", "2025-03-01", "announced");
        release(alpha, SWITCH, JAPAN, "day", "2024-12-01", "announced");
        release(alpha, OTHER, WORLDWIDE, "day", "2019-01-01", "announced");
        genre(alpha, ADVENTURE);

        String beta = game(2, "Beta Upcoming Day");
        release(beta, SWITCH, WORLDWIDE, "day", "2026-09-01", "announced");
        genre(beta, PUZZLE);
        genre(beta, RACING);

        String gamma = game(3, "Gamma Quarter And Month");
        release(gamma, SWITCH, WORLDWIDE, "quarter", "2026-4", "announced");
        release(gamma, SWITCH, EUROPE, "month", "2026-09", "announced");
        igdb(gamma, "70", 10);
        community(gamma, "7.0", 1);

        release(game(4, "Delta Undated"), SWITCH, WORLDWIDE, "unknown", null, "announced");

        String epsilon = game(5, "Epsilon Out And Coming");
        release(epsilon, SWITCH, EUROPE, "day", "2026-12-01", "announced");
        release(epsilon, SWITCH, JAPAN, "day", "2026-01-10", "announced");
        genre(epsilon, PUZZLE);

        String zeta = game(6, "Zeta Cancelled Here");
        release(zeta, SWITCH, WORLDWIDE, "day", "2021-01-01", "cancelled");
        release(zeta, OTHER, WORLDWIDE, "day", "2020-01-01", "announced");

        release(game(7, "Eta Elsewhere"), OTHER, WORLDWIDE, "day", "2020-01-01", "announced");
        release(
                game(8, "Theta Variant Only"),
                SWITCH_VARIANT,
                WORLDWIDE,
                "day",
                "2023-05-05",
                "announced");
        release(game(9, "Iota Delayed"), SWITCH, WORLDWIDE, "day", "2026-07-01", "delayed");

        String kappa = game(10, "Kappa");
        release(kappa, SWITCH, WORLDWIDE, "day", "2023-03-03", "announced");
        genre(kappa, ADVENTURE);
        alias(kappa, "Hidden Name", "approved");
        alias(kappa, "Pending Alias", "pending");
        igdb(kappa, "80", 300);

        String tieB = game(11, "Tie B");
        release(tieB, SWITCH, WORLDWIDE, "day", "2022-02-02", "announced");
        igdb(tieB, "90", 50);
        release(game(12, "Tie A"), SWITCH, WORLDWIDE, "day", "2022-02-02", "announced");

        String lambda = game(13, "Lambda Year");
        release(lambda, SWITCH, WORLDWIDE, "year", "2025", "announced");
        community(lambda, "9.0", 2);
    }

    private static String game(int number, String title) {
        String id = "30000000-0000-4000-8000-%012d".formatted(number);
        admin.update(
                "INSERT INTO catalogue.game (game_id, created_at) VALUES (?::uuid, now())", id);
        admin.update(
                """
                INSERT INTO catalogue.game_snapshot
                    (publication_id, game_id, canonical_title, slug, cover_reference, cover_source,
                     cover_usage_mode, cover_alternative_text, cover_usage_status)
                VALUES (?::uuid, ?::uuid, ?, ?, '/assets/covers/fallback.svg', 'VideoGame Platform',
                        'product_owned', 'Platform games fixture', 'approved')
                """,
                PUBLICATION,
                id,
                title,
                "platform-games-" + number);
        return id;
    }

    private static void release(
            String game,
            String platform,
            String region,
            String precision,
            String value,
            String status) {
        String releaseId = "40000000-0000-4000-8000-%012d".formatted(++releaseSequence);
        admin.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                releaseId,
                game);
        Integer year =
                switch (precision) {
                    case "month", "quarter", "year" -> Integer.valueOf(value.substring(0, 4));
                    default -> null;
                };
        Integer month = "month".equals(precision) ? Integer.valueOf(value.substring(5)) : null;
        Integer quarter = "quarter".equals(precision) ? Integer.valueOf(value.substring(5)) : null;
        admin.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_quarter, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?, ?, ?, ?, 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required')",
                PUBLICATION,
                releaseId,
                game,
                platform,
                region,
                precision,
                "day".equals(precision) ? value : null,
                year,
                month,
                quarter,
                status);
    }

    private static void genre(String game, String genre) {
        admin.update(
                "INSERT INTO catalogue.game_genre (game_id, genre_id) VALUES (?::uuid, ?::uuid)",
                game,
                genre);
    }

    private static void alias(String game, String alias, String approval) {
        admin.update(
                "INSERT INTO catalogue.game_alias (publication_id, game_id, alias, alias_kind, approval_status, source_kind, source_name) VALUES (?::uuid, ?::uuid, ?, 'alternative', ?, 'product_curated', 'platform games fixture')",
                PUBLICATION,
                game,
                alias,
                approval);
    }

    private static void igdb(String game, String score, long count) {
        admin.update(
                "INSERT INTO catalogue.game_external_score (game_id, source_name, score_kind, score, score_count, source_url, observed_at, changed_at) VALUES (?::uuid, 'IGDB', 'player', ?::numeric, ?, 'https://www.igdb.com/games/fixture', now(), now())",
                game,
                score,
                count);
    }

    private static void community(String game, String mean, int count) {
        admin.update(
                "INSERT INTO catalogue.game_community_score (game_id, mean, exact_mean, rating_count) VALUES (?::uuid, ?::numeric, ?::numeric, ?)",
                game,
                mean,
                mean,
                count);
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
