package com.videogameplatform.api.delivery.catalogue.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** UC-018 over HTTP: contract, sources kept apart, positions, validators and closed parameters. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(GameRankingApiIntegrationTest.FixedClock.class)
@Execution(ExecutionMode.SAME_THREAD)
class GameRankingApiIntegrationTest {
    private static final String DB =
            PostgreSqlTestDatabase.isolatedDatabaseName("game_ranking_api");
    private static final String PATH = "/api/v1/game-rankings";
    private static final String GAME_ONE = "30000000-0000-4000-8000-000000000386";
    private static final String GAME_TWO = "30000000-0000-4000-8000-000000000387";
    private static final String GAME_THREE = "30000000-0000-4000-8000-000000000388";
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenApiResponseContract CONTRACT =
            OpenApiResponseContract.load("/game-rankings");

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private CatalogueProviderPort provider;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @BeforeEach
    void seedRankedGames() {
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('20000000-0000-4000-8000-000000000386', 'ranking-api', 'Mundial') ON CONFLICT DO NOTHING");
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES ('10000000-0000-4000-8000-000000000386', 'ranking-api', 'Ranking Station') ON CONFLICT DO NOTHING");
        game(GAME_ONE, "Ranking One", 1);
        game(GAME_TWO, "Ranking Two", 2);
        game(GAME_THREE, "Ranking Three", 3);
    }

    @AfterEach
    void clear() {
        try {
            verify(provider, never()).fetchWorks(org.mockito.ArgumentMatchers.any());
        } finally {
            for (String game : List.of(GAME_ONE, GAME_TWO, GAME_THREE)) {
                jdbc.update(
                        "DELETE FROM catalogue.game_external_score WHERE game_id=?::uuid", game);
                jdbc.update(
                        "DELETE FROM catalogue.game_community_score WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.release_snapshot WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_release WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_snapshot WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game WHERE game_id=?::uuid", game);
            }
        }
    }

    @Test
    void ranksIgdbPlayersByDefaultWithConsecutivePositionsAcrossPages() throws Exception {
        external(GAME_ONE, "88.04", 200);
        external(GAME_TWO, "87.96", 5000);
        external(GAME_THREE, "90", 100);

        var first = get(PATH + "?pageSize=2", null);
        CONTRACT.assertJsonResponse(first, 200, "GameRankingPage");
        assertThat(first.headers().firstValue("Cache-Control")).contains("public, no-cache");
        JsonNode body = JSON.readTree(first.body());
        assertThat(body.path("source").stringValue()).isEqualTo("igdb_players");
        assertThat(ids(body)).containsExactly(GAME_THREE, GAME_ONE);
        assertThat(body.path("items").get(0).path("position").asLong()).isEqualTo(1);
        // Both show 8.8; the unrounded mean decides, not the larger count.
        assertThat(body.path("items").get(1).path("score").path("value").asDouble()).isEqualTo(8.8);
        assertThat(body.path("items").get(0).path("score").path("count").asLong()).isEqualTo(100);
        assertThat(
                        body.path("items")
                                .get(0)
                                .path("score")
                                .path("attribution")
                                .path("label")
                                .stringValue())
                .isEqualTo("Jugadores IGDB");
        assertThat(body.path("page").path("totalItems").asLong()).isEqualTo(3);

        JsonNode second = JSON.readTree(get(PATH + "?page=2&pageSize=2", null).body());
        assertThat(ids(second)).containsExactly(GAME_TWO);
        assertThat(second.path("items").get(0).path("position").asLong()).isEqualTo(3);
        assertThat(second.path("items").get(0).path("score").path("value").asDouble())
                .isEqualTo(8.8);
    }

    @Test
    void ranksTheCommunityOnItsOwnAndNeverFillsItFromIgdb() throws Exception {
        external(GAME_ONE, "95", 100);
        var empty = get(PATH + "?source=gameometro_community", null);
        CONTRACT.assertJsonResponse(empty, 200, "GameRankingPage");
        assertThat(JSON.readTree(empty.body()).path("items")).isEmpty();

        community(GAME_TWO, "8.250000000000000000", "8.3", 4);
        community(GAME_THREE, "8.333333333333333333", "8.3", 3);
        var ranked = get(PATH + "?source=gameometro_community", null);
        CONTRACT.assertJsonResponse(ranked, 200, "GameRankingPage");
        JsonNode body = JSON.readTree(ranked.body());
        assertThat(body.path("source").stringValue()).isEqualTo("gameometro_community");
        assertThat(ids(body)).containsExactly(GAME_THREE, GAME_TWO);
        JsonNode score = body.path("items").get(0).path("score");
        assertThat(score.path("source").stringValue()).isEqualTo("gameometro");
        assertThat(score.path("kind").stringValue()).isEqualTo("community");
        assertThat(score.path("value").asDouble()).isEqualTo(8.3);
        assertThat(score.path("count").asInt()).isEqualTo(3);
        assertThat(score.path("attribution").isMissingNode()).isTrue();
    }

    @Test
    void revalidatesWhenAScoreChangesThePage() throws Exception {
        external(GAME_ONE, "70", 100);
        var first = get(PATH, null);
        String tag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(get(PATH, tag).statusCode()).isEqualTo(304);

        external(GAME_TWO, "71", 100);
        var changed = get(PATH, tag);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(ids(JSON.readTree(changed.body()))).containsExactly(GAME_TWO, GAME_ONE);
    }

    @Test
    void rejectsAnUnknownSourceRepeatedSourceBadPageAndUnknownParameter() throws Exception {
        assertProblem(get(PATH + "?source=critics", null), "SORT_INVALID");
        assertProblem(
                get(PATH + "?source=igdb_players&source=gameometro_community", null),
                "SORT_INVALID");
        assertProblem(get(PATH + "?pageSize=101", null), "PAGINATION_INVALID");
        assertProblem(get(PATH + "?page=0", null), "PAGINATION_INVALID");
        assertProblem(get(PATH + "?platformId=x", null), "REQUEST_PARAMETER_UNKNOWN");
        assertProblem(get(PATH + "?platformIds=x", null), "PLATFORM_NOT_SUPPORTED");
    }

    @Test
    void ranksAPlatformByTheGlobalScoreOfGamesAlreadyReleasedThere() throws Exception {
        external(GAME_ONE, "80", 200);
        external(GAME_TWO, "95", 200);
        external(GAME_THREE, "85", 200);
        // GAME_TWO leads globally, but its PlayStation 5 port is still to come.
        release(GAME_ONE, 4, PS5, "2021-06-11");
        release(GAME_ONE, 5, PS5, "2022-01-01");
        release(GAME_TWO, 6, PS5, "2026-12-01");

        var scoped = get(PATH + "?platformIds=" + PS5, null);
        CONTRACT.assertJsonResponse(scoped, 200, "GameRankingPage");
        JsonNode body = JSON.readTree(scoped.body());
        assertThat(ids(body)).containsExactly(GAME_ONE);
        assertThat(body.path("page").path("totalItems").asLong()).isEqualTo(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("position").asLong()).isEqualTo(1);
        assertThat(item.path("score").path("value").asDouble()).isEqualTo(8.0);
        assertThat(item.path("platformRelease").path("platform").path("platformId").stringValue())
                .isEqualTo(PS5);
        assertThat(item.path("platformRelease").path("releaseDate").path("value").stringValue())
                .isEqualTo("2021-06-11");
        assertThat(item.path("platformRelease").path("status").stringValue()).isEqualTo("released");

        var global = JSON.readTree(get(PATH, null).body());
        assertThat(ids(global)).containsExactly(GAME_TWO, GAME_THREE, GAME_ONE);
        assertThat(global.path("items").get(0).has("platformRelease")).isFalse();

        var community = get(PATH + "?source=gameometro_community&platformIds=" + PS5, null);
        CONTRACT.assertJsonResponse(community, 200, "GameRankingPage");
        assertThat(JSON.readTree(community.body()).path("items")).isEmpty();
    }

    private void assertProblem(HttpResponse<String> response, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(response.body()).path("code").stringValue()).isEqualTo(code);
    }

    private static List<String> ids(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("items").forEach(item -> ids.add(item.path("gameId").stringValue()));
        return ids;
    }

    private void game(String id, String title, int number) {
        jdbc.update("INSERT INTO catalogue.game (game_id, created_at) VALUES (?::uuid, now())", id);
        jdbc.update(
                """
                INSERT INTO catalogue.game_snapshot
                    (publication_id, game_id, canonical_title, slug, cover_reference, cover_source,
                     cover_usage_mode, cover_alternative_text, cover_usage_status)
                SELECT publication_id, ?::uuid, ?, ?, '/assets/covers/fallback.svg', 'VideoGame Platform',
                       'product_owned', 'Ranking fixture', 'approved'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                id,
                title,
                "ranking-api-" + number);
        String release = "40000000-0000-4000-8000-00000000038" + number;
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                release,
                id);
        jdbc.update(
                """
                INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status)
                SELECT publication_id, ?::uuid, ?::uuid, '10000000-0000-4000-8000-000000000386', '20000000-0000-4000-8000-000000000386', 'day', DATE '2020-01-01', 'released', 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                release,
                id);
    }

    private void release(String game, int number, String platform, String day) {
        String release = "40000000-0000-4000-8000-00000000039" + number;
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                release,
                game);
        jdbc.update(
                """
                INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status)
                SELECT publication_id, ?::uuid, ?::uuid, ?::uuid, '20000000-0000-4000-8000-000000000386', 'day', ?::date, 'announced', 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                release,
                game,
                platform,
                day);
    }

    private void external(String game, String score, long count) {
        jdbc.update(
                """
                INSERT INTO catalogue.game_external_score(game_id,source_name,score_kind,score,score_count,source_url,observed_at,changed_at)
                VALUES(?::uuid,'IGDB','player',?::numeric,?,'https://www.igdb.com/games/ranking','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')
                """,
                game,
                score,
                count);
    }

    private void community(String game, String exactMean, String mean, int count) {
        jdbc.update(
                "INSERT INTO catalogue.game_community_score (game_id, mean, exact_mean, rating_count) VALUES (?::uuid, ?::numeric, ?::numeric, ?)",
                game,
                mean,
                exactMean,
                count);
    }

    private HttpResponse<String> get(String path, String tag) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (tag != null) request.header("If-None-Match", tag);
        return HttpClient.newHttpClient()
                .send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        }
    }
}
