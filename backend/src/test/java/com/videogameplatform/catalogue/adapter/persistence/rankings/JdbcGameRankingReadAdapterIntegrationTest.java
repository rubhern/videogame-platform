package com.videogameplatform.catalogue.adapter.persistence.rankings;

import static com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source.GAMEOMETRO_COMMUNITY;
import static com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source.IGDB_PLAYERS;
import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.LocalDate;
import java.util.ArrayList;
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

/** UC-018 against real PostgreSQL: selection, eligibility, order, ties, count and pages. */
@Execution(ExecutionMode.SAME_THREAD)
class JdbcGameRankingReadAdapterIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("game_ranking_adapter");
    private static final String PUBLICATION = "00000000-0000-4000-8000-0000000000aa";
    private static final String PLATFORM_A = "10000000-0000-4000-8000-0000000000a1";
    private static final String PLATFORM_B = "10000000-0000-4000-8000-0000000000a2";
    private static final String REGION = "20000000-0000-4000-8000-0000000000a1";
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    private static JdbcTemplate admin;
    private static JdbcGameRankingReadAdapter adapter;
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
                new JdbcGameRankingReadAdapter(
                        new NamedParameterJdbcTemplate(runtime), readTransaction(runtime));
        seed();
    }

    @Test
    void ranksIgdbPlayersByUnroundedMeanThenCountThenGameIdAmongEligibleGamesOnly() {
        var result = adapter.findRankedGames(criteria(IGDB_PLAYERS, 1, 100)).orElseThrow();

        assertThat(result.publicationVersion()).isEqualTo("ranking-fixture");
        assertThat(titles(result))
                .containsExactly(
                        // 93 from exactly the one hundred ratings IGDB players need.
                        "Multi platform",
                        // 92 three times: more ratings first, then the smaller game id.
                        "Tied many votes small id",
                        "Tied many votes large id",
                        "Tied few votes",
                        // 91.96 also shows as 9.2 but its unrounded mean is lower.
                        "Rounds to the same",
                        "Released today",
                        "Last month",
                        "Unknown date verified",
                        // A real zero with ratings is a score, ranked last.
                        "Real zero");
        assertThat(result.totalItems()).isEqualTo(9);
    }

    @Test
    void excludesUnscoredUndersampledFutureCancelledDelayedReviewedAndUnprovenGames() {
        var ranked = titles(adapter.findRankedGames(criteria(IGDB_PLAYERS, 1, 100)).orElseThrow());

        assertThat(ranked)
                .doesNotContain(
                        "Future day",
                        "Current month",
                        "Cancelled",
                        "Delayed",
                        "Review required",
                        "Unknown date unverified",
                        "Checked without score",
                        "Without releases",
                        "Ninety-nine ratings");
    }

    @Test
    void placesAGameOnceWhateverItsPlatformsAndSummarisesAllOfThem() {
        var item =
                adapter.findRankedGames(criteria(IGDB_PLAYERS, 1, 1))
                        .orElseThrow()
                        .items()
                        .getFirst();

        assertThat(item.canonicalTitle()).isEqualTo("Multi platform");
        assertThat(item.releaseSummary().totalPlatforms()).isEqualTo(2);
        assertThat(item.releaseSummary().platforms())
                .extracting(GameRanking.Taxonomy::name)
                .containsExactly("Alpha Station", "Beta Box");
        assertThat(item.releaseSummary().earliestKnownYear()).isEqualTo(2019);
        assertThat(item.score())
                .isInstanceOfSatisfying(
                        GameRanking.Score.IgdbPlayers.class,
                        igdb -> {
                            assertThat(igdb.score().value()).isEqualByComparingTo("93");
                            assertThat(igdb.score().count()).isEqualTo(100);
                            assertThat(igdb.score().attribution())
                                    .hasToString("https://www.igdb.com/games/multi-platform");
                        });
    }

    @Test
    void pagesOneSequenceWithoutGapsOrRepeats() {
        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            var result = adapter.findRankedGames(criteria(IGDB_PLAYERS, page, 2)).orElseThrow();
            assertThat(result.totalItems()).isEqualTo(9);
            paged.addAll(gameIds(result));
        }
        var beyond = adapter.findRankedGames(criteria(IGDB_PLAYERS, 6, 2)).orElseThrow();

        assertThat(paged)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(
                        gameIds(
                                adapter.findRankedGames(criteria(IGDB_PLAYERS, 1, 8))
                                        .orElseThrow()));
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalItems()).isEqualTo(9);
    }

    @Test
    void ranksTheCommunityByItsOwnUnroundedMeanAndNeverBorrowsAnotherSource() {
        var result = adapter.findRankedGames(criteria(GAMEOMETRO_COMMUNITY, 1, 100)).orElseThrow();

        // 9.0 from one rating leads; 8.33… and 8.25 both show 8.3, and the higher unrounded mean
        // leads although it has fewer ratings. A row projected before the unrounded mean existed
        // and an ineligible game stay out, as does every game the community has not rated.
        assertThat(titles(result))
                .containsExactly("Tied few votes", "Tied many votes large id", "Real zero");
        assertThat(result.totalItems()).isEqualTo(3);
        assertThat(result.items())
                .extracting(GameRankingReadPort.Item::score)
                .containsExactly(
                        new GameRanking.Score.Community(new java.math.BigDecimal("9.0"), 1),
                        new GameRanking.Score.Community(new java.math.BigDecimal("8.3"), 3),
                        new GameRanking.Score.Community(new java.math.BigDecimal("8.3"), 4));
    }

    @Test
    void readsNothingWhenNoPublicationIsCurrent() {
        admin.update("UPDATE catalogue.catalogue_publication SET is_current = false");
        try {
            assertThat(adapter.findRankedGames(criteria(IGDB_PLAYERS, 1, 20))).isEmpty();
        } finally {
            admin.update(
                    "UPDATE catalogue.catalogue_publication SET is_current = true WHERE publication_id = ?::uuid",
                    PUBLICATION);
        }
    }

    private static GameRankingReadPort.Criteria criteria(Source source, int page, int size) {
        return new GameRankingReadPort.Criteria(
                source,
                TODAY,
                new GameRankingReadPort.Pagination(page, size, (long) (page - 1) * size),
                3);
    }

    private static List<String> titles(GameRankingReadPort.Result result) {
        return result.items().stream().map(GameRankingReadPort.Item::canonicalTitle).toList();
    }

    private static List<String> gameIds(GameRankingReadPort.Result result) {
        return result.items().stream().map(GameRankingReadPort.Item::gameId).toList();
    }

    private static void seed() {
        admin.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) VALUES (?::uuid, 'ranking-fixture', now(), now(), 'product_curated', 'ranking fixture', true)",
                PUBLICATION);
        admin.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES (?::uuid, 'ranking-b', 'Beta Box'), (?::uuid, 'ranking-a', 'Alpha Station')",
                PLATFORM_B,
                PLATFORM_A);
        admin.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES (?::uuid, 'ranking-region', 'Mundial')",
                REGION);

        String multi = game(1, "Multi platform");
        release(
                multi,
                PLATFORM_A,
                "day",
                "2019-05-01",
                "released",
                "provider_only",
                "not_required");
        release(
                multi,
                PLATFORM_B,
                "day",
                "2020-05-01",
                "released",
                "provider_only",
                "not_required");
        release(
                multi,
                PLATFORM_B,
                "day",
                "2021-05-01",
                "cancelled",
                "provider_only",
                "not_required");
        igdb(multi, "93", 100);

        String tiedLarge = eligible(game(3, "Tied many votes large id"));
        String tiedSmall = eligible(game(2, "Tied many votes small id"));
        String tiedFew = eligible(game(4, "Tied few votes"));
        igdb(tiedLarge, "92", 500);
        igdb(tiedSmall, "92", 500);
        igdb(tiedFew, "92", 150);
        igdb(eligible(game(5, "Rounds to the same")), "91.96", 9999);

        String today = game(6, "Released today");
        release(
                today,
                PLATFORM_A,
                "day",
                TODAY.toString(),
                "announced",
                "provider_only",
                "not_required");
        igdb(today, "91", 300);
        String lastMonth = game(7, "Last month");
        release(
                lastMonth,
                PLATFORM_A,
                "month",
                "2026-07",
                "announced",
                "provider_only",
                "not_required");
        igdb(lastMonth, "90.5", 300);
        String unknownVerified = game(8, "Unknown date verified");
        release(
                unknownVerified,
                PLATFORM_A,
                "unknown",
                null,
                "released",
                "verified",
                "not_required");
        igdb(unknownVerified, "90", 300);
        String zero = eligible(game(9, "Real zero"));
        igdb(zero, "0", 300);

        String future = game(10, "Future day");
        release(
                future,
                PLATFORM_A,
                "day",
                "2026-08-14",
                "announced",
                "provider_only",
                "not_required");
        igdb(future, "99", 500);
        String currentMonth = game(11, "Current month");
        release(
                currentMonth,
                PLATFORM_A,
                "month",
                "2026-08",
                "announced",
                "provider_only",
                "not_required");
        igdb(currentMonth, "99", 500);
        String cancelled = game(12, "Cancelled");
        release(
                cancelled,
                PLATFORM_A,
                "day",
                "2020-01-01",
                "cancelled",
                "verified",
                "not_required");
        igdb(cancelled, "99", 500);
        String delayed = game(13, "Delayed");
        release(delayed, PLATFORM_A, "day", "2020-01-01", "delayed", "verified", "not_required");
        igdb(delayed, "99", 500);
        String reviewed = game(14, "Review required");
        release(reviewed, PLATFORM_A, "day", "2020-01-01", "released", "provider_only", "required");
        igdb(reviewed, "99", 500);
        String unverified = game(15, "Unknown date unverified");
        release(
                unverified,
                PLATFORM_A,
                "unknown",
                null,
                "released",
                "provider_only",
                "not_required");
        igdb(unverified, "99", 500);
        String checked = eligible(game(16, "Checked without score"));
        admin.update(
                "INSERT INTO catalogue.game_external_score (game_id, source_name, score_kind, score, score_count, source_url, observed_at, changed_at) VALUES (?::uuid, 'IGDB', 'player', NULL, NULL, NULL, now(), now())",
                checked);
        igdb(game(17, "Without releases"), "99", 500);
        // Ninety-nine IGDB ratings are one short of a place, however high their mean.
        igdb(eligible(game(18, "Ninety-nine ratings")), "99", 99);

        community(tiedFew, "9.000000000000000000", "9.0", 1);
        community(tiedLarge, "8.333333333333333333", "8.3", 3);
        community(zero, "8.250000000000000000", "8.3", 4);
        // Projected before #386: no unrounded mean yet, so not ranked until the backfill.
        admin.update(
                "INSERT INTO catalogue.game_community_score (game_id, mean, rating_count) VALUES (?::uuid, 9.9, 7)",
                tiedSmall);
        community(cancelled, "10.000000000000000000", "10.0", 2);
    }

    private static String gameId(int number) {
        return "30000000-0000-4000-8000-%012d".formatted(number);
    }

    private static String game(int number, String title) {
        String id = gameId(number);
        admin.update(
                "INSERT INTO catalogue.game (game_id, created_at) VALUES (?::uuid, now())", id);
        admin.update(
                """
                INSERT INTO catalogue.game_snapshot
                    (publication_id, game_id, canonical_title, slug, cover_reference, cover_source,
                     cover_usage_mode, cover_alternative_text, cover_usage_status)
                VALUES (?::uuid, ?::uuid, ?, ?, '/assets/covers/fallback.svg', 'VideoGame Platform',
                        'product_owned', 'Ranking fixture', 'approved')
                """,
                PUBLICATION,
                id,
                title,
                "ranking-" + id.substring(id.length() - 4));
        return id;
    }

    private static String eligible(String game) {
        release(game, PLATFORM_A, "day", "2020-01-01", "released", "provider_only", "not_required");
        return game;
    }

    private static void release(
            String game,
            String platform,
            String precision,
            String value,
            String status,
            String verification,
            String review) {
        String releaseId = "40000000-0000-4000-8000-%012d".formatted(++releaseSequence);
        admin.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                releaseId,
                game);
        Integer year = "month".equals(precision) ? Integer.valueOf(value.substring(0, 4)) : null;
        Integer month = "month".equals(precision) ? Integer.valueOf(value.substring(5)) : null;
        admin.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_month, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?, ?, ?, 'external_provider', 'IGDB', 'release_date', now(), ?, ?)",
                PUBLICATION,
                releaseId,
                game,
                platform,
                REGION,
                precision,
                "day".equals(precision) ? value : null,
                year,
                month,
                status,
                verification,
                review);
    }

    private static void igdb(String game, String score, long count) {
        admin.update(
                "INSERT INTO catalogue.game_external_score (game_id, source_name, score_kind, score, score_count, source_url, observed_at, changed_at) VALUES (?::uuid, 'IGDB', 'player', ?::numeric, ?, ?, now(), now())",
                game,
                score,
                count,
                "https://www.igdb.com/games/"
                        + admin.queryForObject(
                                "SELECT lower(replace(canonical_title, ' ', '-')) FROM catalogue.game_snapshot WHERE game_id = ?::uuid LIMIT 1",
                                String.class,
                                game));
    }

    private static void community(String game, String exactMean, String mean, int count) {
        admin.update(
                "INSERT INTO catalogue.game_community_score (game_id, mean, exact_mean, rating_count) VALUES (?::uuid, ?::numeric, ?::numeric, ?)",
                game,
                mean,
                exactMean,
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
