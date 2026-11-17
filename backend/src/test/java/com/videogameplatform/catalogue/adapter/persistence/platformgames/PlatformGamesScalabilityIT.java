package com.videogameplatform.catalogue.adapter.persistence.platformgames;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.ReleaseState;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.Sort;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort.Criteria;
import com.videogameplatform.catalogue.domain.CatalogueSearchText;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in local scalability evidence for UC-019; intentionally excluded from the normal test
 * pattern. Run with {@code -Dtest=PlatformGamesScalabilityIT -Dplatform.games.scale.games=100000}.
 */
class PlatformGamesScalabilityIT {

    private static final String PUBLICATION_ID = "90000000-0000-4000-8000-000000000389";
    private static final String LARGE_PLATFORM = "91000000-0000-4000-8000-000000000001";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void selectsPresentsCountsAndPagesAPlatformHoldingEveryGameInPostgreSql() throws Exception {
        int games = Integer.getInteger("platform.games.scale.games", 100_000);
        if (games < 10_000 || games > 1_000_000) {
            throw new IllegalArgumentException(
                    "platform.games.scale.games must be between 10000 and 1000000");
        }
        String databaseName =
                PostgreSqlTestDatabase.isolatedDatabaseName("platform_games_scale_" + games);
        PostgreSqlTestDatabase.createDatabase(databaseName);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(databaseName),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        seed(
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(databaseName),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword())),
                games);
        DataSource runtime =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(databaseName),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        var jdbc = new NamedParameterJdbcTemplate(runtime);
        TransactionTemplate transaction = readTransaction(runtime);
        var adapter = new JdbcPlatformGamesReadAdapter(jdbc, transaction);

        Map<String, Criteria> cases =
                Map.of(
                        "latest", criteria(Sort.RELEASE_DATE_DESC, null, null, List.of(), 1),
                        "latest-page-200",
                                criteria(Sort.RELEASE_DATE_DESC, null, null, List.of(), 200),
                        "upcoming-earliest",
                                criteria(
                                        Sort.RELEASE_DATE_ASC,
                                        ReleaseState.UPCOMING,
                                        null,
                                        List.of(),
                                        1),
                        "genre-player-score",
                                criteria(
                                        Sort.EXTERNAL_PLAYER_SCORE,
                                        null,
                                        null,
                                        List.of("scale-genre-3"),
                                        1),
                        "search-title", criteria(Sort.TITLE_ASC, null, "juego 12", List.of(), 1));
        for (var entry : cases.entrySet()) {
            Criteria criteria = entry.getValue();
            var result = adapter.findPlatformGames(criteria).orElseThrow();
            // A page of 24 stays 24 games in Java whatever the platform holds.
            assertThat(result.items()).hasSizeLessThanOrEqualTo(24);
            assertThat(result.items())
                    .extracting(PlatformGamesReadPort.Item::gameId)
                    .doesNotHaveDuplicates();
            assertThat(result.totalItems()).isPositive();

            List<String> genreIds =
                    criteria.genreIds().isEmpty()
                            ? List.of()
                            : List.of(
                                    jdbc.queryForObject(
                                            "SELECT genre_id::text FROM catalogue.genre WHERE code = :code",
                                            Map.of("code", criteria.genreIds().getFirst()),
                                            String.class));
            Map<String, Object> parameters =
                    PlatformGamesSql.parameters(
                            PUBLICATION_ID, criteria, List.of(LARGE_PLATFORM), genreIds);
            recordPlan(
                    games,
                    entry.getKey(),
                    explain(jdbc, transaction, PlatformGamesSql.read(criteria), parameters));
        }
    }

    private static Criteria criteria(
            Sort sort, ReleaseState state, String text, List<String> genres, int page) {
        PlatformGamesReadPort.Search search = null;
        if (text != null) {
            CatalogueSearchText normalized = CatalogueSearchText.of(text);
            search = new PlatformGamesReadPort.Search(normalized.normalized(), normalized.tokens());
        }
        return new Criteria(
                List.of(LARGE_PLATFORM),
                search,
                state,
                null,
                null,
                genres,
                sort,
                TODAY,
                new PlatformGamesReadPort.Pagination(page, 24, (long) (page - 1) * 24));
    }

    /**
     * Every game is on the large platform with two regional releases, one in five also on a
     * second platform; one in ten is still to come, one in fifty undated and one in a hundred
     * cancelled there. Each game holds two of twenty genres, four in five an IGDB score, one in
     * seven an approved alias.
     */
    private static void seed(JdbcTemplate jdbc, int games) {
        jdbc.update(
                "INSERT INTO catalogue.catalogue_publication (publication_id, catalogue_version, published_at, last_synchronized_at, source_kind, source_name, is_current) VALUES (?::uuid, ?, now(), now(), 'product_curated', 'platform games scale fixture', true)",
                PUBLICATION_ID,
                "platform-games-scale-" + games);
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES (?::uuid, 'scale-large', 'Scale Large'), ('91000000-0000-4000-8000-000000000002', 'scale-small', 'Scale Small')",
                LARGE_PLATFORM);
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('92000000-0000-4000-8000-000000000001', 'scale-europe', 'Europa'), ('92000000-0000-4000-8000-000000000002', 'scale-japan', 'Japón')");
        jdbc.update(
                "INSERT INTO catalogue.genre (genre_id, code, display_name) SELECT md5('scale-genre-' || g)::uuid, 'scale-genre-' || g, 'Género ' || g FROM generate_series(1, 20) g");
        jdbc.update(
                "INSERT INTO catalogue.game (game_id, created_at) SELECT md5('scale-game-' || n)::uuid, now() FROM generate_series(1, ?) n",
                games);
        jdbc.update(
                "INSERT INTO catalogue.game_snapshot (publication_id, game_id, canonical_title, slug, cover_reference, cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status) SELECT ?::uuid, md5('scale-game-' || n)::uuid, 'Escala Juego ' || n, 'scale-game-' || n, '/assets/covers/fallback.svg', 'VideoGame Platform', 'product_owned', 'Scale fallback', 'approved' FROM generate_series(1, ?) n",
                PUBLICATION_ID,
                games);
        jdbc.update(
                "INSERT INTO catalogue.game_alias (publication_id, game_id, alias, alias_kind, approval_status, source_kind, source_name) SELECT ?::uuid, md5('scale-game-' || n)::uuid, 'Alias Escala ' || n, 'alternative', 'approved', 'product_curated', 'scale fixture' FROM generate_series(1, ?) n WHERE n % 7 = 0",
                PUBLICATION_ID, games);
        jdbc.update(
                "INSERT INTO catalogue.game_genre (game_id, genre_id) SELECT md5('scale-game-' || n)::uuid, md5('scale-genre-' || (1 + (n + k) % 20))::uuid FROM generate_series(1, ?) n, generate_series(0, 1) k",
                games);
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) SELECT md5('scale-release-' || n || '-' || r)::uuid, md5('scale-game-' || n)::uuid, now() FROM generate_series(1, ?) n, generate_series(1, 3) r WHERE r < 3 OR n % 5 = 0",
                games);
        jdbc.update(
                """
                INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status)
                SELECT ?::uuid, md5('scale-release-' || n || '-' || r)::uuid, md5('scale-game-' || n)::uuid,
                       CASE WHEN r = 3 THEN '91000000-0000-4000-8000-000000000002'::uuid ELSE ?::uuid END,
                       ('92000000-0000-4000-8000-00000000000' || r % 2 + 1)::uuid,
                       CASE WHEN n % 50 = 0 THEN 'unknown' ELSE 'day' END,
                       CASE WHEN n % 50 = 0 THEN NULL
                            WHEN n % 10 = 0 THEN DATE '2026-11-01' + ((n + r) % 400)
                            ELSE DATE '1995-01-01' + ((n * 3 + r) % 11000) END,
                       CASE WHEN n % 100 = 1 THEN 'cancelled' ELSE 'announced' END,
                       'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required'
                FROM generate_series(1, ?) n, generate_series(1, 3) r
                WHERE r < 3 OR n % 5 = 0
                """,
                PUBLICATION_ID, LARGE_PLATFORM, games);
        jdbc.update(
                """
                INSERT INTO catalogue.game_external_score (game_id, source_name, score_kind, score, score_count, source_url, observed_at, changed_at)
                SELECT md5('scale-game-' || n)::uuid, 'IGDB', 'player', 60 + (n % 400) / 10.0, 1 + (n * 7) % 1000,
                       'https://www.igdb.com/games/scale-' || n, now(), now()
                FROM generate_series(1, ?) n WHERE n % 5 <> 0
                """,
                games);
        for (String table :
                List.of(
                        "game_snapshot",
                        "game_alias",
                        "game_genre",
                        "release_snapshot",
                        "game_external_score")) {
            jdbc.execute("ANALYZE catalogue." + table);
        }
    }

    private static TransactionTemplate readTransaction(DataSource dataSource) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(30);
        return transaction;
    }

    private static JsonNode explain(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transaction,
            String sql,
            Map<String, Object> parameters) {
        // As the adapter reads it: inside its transaction, without JIT compilation.
        String json =
                transaction.execute(
                        status -> {
                            jdbc.getJdbcOperations().execute("SET LOCAL jit = off");
                            return jdbc.queryForObject(
                                    "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql,
                                    parameters,
                                    String.class);
                        });
        return OBJECT_MAPPER.readTree(json).get(0);
    }

    private static void recordPlan(int games, String operation, JsonNode plan) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "query-plans"));
        Path output = directory.resolve("platform-games-" + games + "-" + operation + ".json");
        Files.writeString(
                output, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        System.out.printf(
                "Platform games %s: games=%d, executionMs=%s, rows=%s, sharedHitBlocks=%s, plan=%s%n",
                operation,
                games,
                plan.path("Execution Time"),
                plan.path("Plan").path("Actual Rows"),
                plan.path("Plan").path("Shared Hit Blocks"),
                output);
    }
}
