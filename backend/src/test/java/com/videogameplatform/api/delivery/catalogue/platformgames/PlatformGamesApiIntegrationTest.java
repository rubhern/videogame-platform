package com.videogameplatform.api.delivery.catalogue.platformgames;

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

/** UC-019 over HTTP: contract, presented releases, filters, validators and closed parameters. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(PlatformGamesApiIntegrationTest.FixedClock.class)
@Execution(ExecutionMode.SAME_THREAD)
class PlatformGamesApiIntegrationTest {
    private static final String DB =
            PostgreSqlTestDatabase.isolatedDatabaseName("platform_games_api");
    private static final String PLATFORM = "10000000-0000-4000-8000-000000000389";
    private static final String OTHER_PLATFORM = "10000000-0000-4000-8000-000000000390";
    private static final String PATH = "/api/v1/platform-games?platformIds=" + PLATFORM;
    private static final String RELEASED = "30000000-0000-4000-8000-000000000389";
    private static final String UPCOMING = "30000000-0000-4000-8000-000000000390";
    private static final String UNDATED = "30000000-0000-4000-8000-000000000391";
    private static final String ELSEWHERE = "30000000-0000-4000-8000-000000000392";
    private static final List<String> GAMES = List.of(RELEASED, UPCOMING, UNDATED, ELSEWHERE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenApiResponseContract CONTRACT =
            OpenApiResponseContract.load("/platform-games");

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private CatalogueProviderPort provider;
    private int releaseSequence;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @BeforeEach
    void seedPlatformGames() {
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('20000000-0000-4000-8000-000000000389', 'platform-games-api', 'Mundial') ON CONFLICT DO NOTHING");
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES (?::uuid, 'platform-games-api', 'Catalogue Station'), (?::uuid, 'platform-games-api-other', 'Other Station') ON CONFLICT DO NOTHING",
                PLATFORM,
                OTHER_PLATFORM);
        jdbc.update(
                "INSERT INTO catalogue.genre (genre_id, code, display_name) VALUES ('50000000-0000-4000-8000-000000000389', 'platform-games-api', 'Exploración') ON CONFLICT DO NOTHING");
        game(RELEASED, "Catalogue Released", 1);
        release(RELEASED, PLATFORM, "2026-03-20");
        release(RELEASED, PLATFORM, "2026-11-20");
        jdbc.update(
                "INSERT INTO catalogue.game_genre (game_id, genre_id) VALUES (?::uuid, '50000000-0000-4000-8000-000000000389')",
                RELEASED);
        jdbc.update(
                """
                INSERT INTO catalogue.game_alias (publication_id, game_id, alias, alias_kind, approval_status, source_kind, source_name)
                SELECT publication_id, ?::uuid, 'Secret Codename', 'alternative', 'approved', 'product_curated', 'Platform games fixture'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                RELEASED);
        game(UPCOMING, "Catalogue Upcoming", 2);
        release(UPCOMING, PLATFORM, "2026-12-04");
        game(UNDATED, "Catalogue Undated", 3);
        release(UNDATED, PLATFORM, null);
        game(ELSEWHERE, "Catalogue Elsewhere", 4);
        release(ELSEWHERE, OTHER_PLATFORM, "2020-01-01");
    }

    @AfterEach
    void clear() {
        try {
            verify(provider, never()).fetchWorks(org.mockito.ArgumentMatchers.any());
        } finally {
            for (String game : GAMES) {
                jdbc.update(
                        "DELETE FROM catalogue.game_external_score WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_genre WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_alias WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.release_snapshot WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_release WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_snapshot WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game WHERE game_id=?::uuid", game);
            }
        }
    }

    @Test
    void browsesThePlatformWithoutAQueryLatestFirstAndPresentsEachGameOnce() throws Exception {
        external(RELEASED, "84", 212);

        var response = get(PATH, null);
        CONTRACT.assertJsonResponse(response, 200, "PlatformGamesPage");
        assertThat(response.headers().firstValue("Cache-Control")).contains("public, no-cache");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("platforms").get(0).path("name").stringValue())
                .isEqualTo("Catalogue Station");
        assertThat(ids(body)).containsExactly(UPCOMING, RELEASED, UNDATED);
        assertThat(body.path("page").path("totalItems").asLong()).isEqualTo(3);

        JsonNode released = body.path("items").get(1);
        // Out since March, so presented by that release although another one is still to come.
        assertThat(released.path("platformRelease").path("releaseDate").path("value").stringValue())
                .isEqualTo("2026-03-20");
        assertThat(released.path("platformRelease").path("status").stringValue())
                .isEqualTo("released");
        assertThat(released.path("genres").get(0).path("name").stringValue())
                .isEqualTo("Exploración");
        assertThat(released.path("scores").path("external").path("value").asDouble())
                .isEqualTo(8.4);
        assertThat(released.path("scores").path("community").path("status").stringValue())
                .isEqualTo("available");
        assertThat(body.path("items").get(0).path("platformRelease").path("status").stringValue())
                .isEqualTo("scheduled");
        JsonNode undated = body.path("items").get(2).path("platformRelease");
        assertThat(undated.path("releaseDate").path("precision").stringValue())
                .isEqualTo("unknown");
        assertThat(undated.path("status").stringValue()).isEqualTo("announced");
        assertThat(body.path("availableFilters").path("earliestYear").asInt()).isEqualTo(2026);
    }

    @Test
    void narrowsByReleaseStateSearchGenreAndYearsAndOrdersByTitle() throws Exception {
        assertThat(ids(JSON.readTree(get(PATH + "&releaseState=upcoming", null).body())))
                .containsExactly(UPCOMING, UNDATED);
        assertThat(ids(JSON.readTree(get(PATH + "&releaseState=released", null).body())))
                .containsExactly(RELEASED);

        var alias = get(PATH + "&q=secret", null);
        CONTRACT.assertJsonResponse(alias, 200, "PlatformGamesPage");
        JsonNode aliased = JSON.readTree(alias.body());
        assertThat(ids(aliased)).containsExactly(RELEASED);
        assertThat(aliased.path("items").get(0).path("matchedAlias").stringValue())
                .isEqualTo("Secret Codename");
        assertThat(ids(JSON.readTree(get(PATH + "&q=elsewhere", null).body()))).isEmpty();

        JsonNode genre = JSON.readTree(get(PATH + "&genreIds=platform-games-api", null).body());
        assertThat(ids(genre)).containsExactly(RELEASED);
        assertThat(
                        genre.path("availableFilters")
                                .path("genres")
                                .get(0)
                                .path("genreId")
                                .stringValue())
                .isEqualTo("platform-games-api");

        assertThat(ids(JSON.readTree(get(PATH + "&fromYear=2026&toYear=2026", null).body())))
                .containsExactly(UPCOMING, RELEASED);
        assertThat(ids(JSON.readTree(get(PATH + "&sort=title_asc", null).body())))
                .containsExactly(RELEASED, UNDATED, UPCOMING);
        assertThat(
                        ids(
                                JSON.readTree(
                                        get(
                                                        PATH
                                                                + "&platformIds="
                                                                + OTHER_PLATFORM
                                                                + "&sort=release_date_asc",
                                                        null)
                                                .body())))
                .containsExactly(ELSEWHERE, RELEASED, UPCOMING, UNDATED);
    }

    @Test
    void revalidatesWhenAScoreChangesThePage() throws Exception {
        var first = get(PATH, null);
        String tag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(get(PATH, tag).statusCode()).isEqualTo(304);

        external(UPCOMING, "70", 40);
        assertThat(get(PATH, tag).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsAnInvalidScopeQueryFilterOrderPageAndParameter() throws Exception {
        var missing = get("/api/v1/platform-games", null);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(missing.body()).path("code").stringValue())
                .isEqualTo("REQUEST_MALFORMED");
        assertProblem(
                get("/api/v1/platform-games?platformIds=platform_invented", null),
                "PLATFORM_NOT_SUPPORTED",
                "/query/platformIds");
        assertProblem(get(PATH + "&q=%C2%BF%21", null), "SEARCH_QUERY_INVALID", "/query/q");
        assertProblem(
                get(PATH + "&fromYear=2027&toYear=2026", null), "FILTER_INVALID", "/query/toYear");
        assertProblem(get(PATH + "&fromYear=0", null), "FILTER_INVALID", "/query/fromYear");
        assertProblem(get(PATH + "&genreIds=invented", null), "FILTER_INVALID", "/query/genreIds");
        assertProblem(
                get(PATH + "&releaseState=soon", null), "FILTER_INVALID", "/query/releaseState");
        assertProblem(get(PATH + "&sort=hype", null), "SORT_INVALID", "/query/sort");
        assertProblem(get(PATH + "&pageSize=101", null), "PAGINATION_INVALID", "/query/pageSize");
        assertProblem(
                get(PATH + "&regionIds=x", null), "REQUEST_PARAMETER_UNKNOWN", "/query/regionIds");
    }

    private void assertProblem(HttpResponse<String> response, String code, String pointer)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.path("code").stringValue()).isEqualTo(code);
        assertThat(problem.toString()).contains(pointer);
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
                       'product_owned', 'Platform games fixture', 'approved'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                id,
                title,
                "platform-games-api-" + number);
    }

    private void release(String game, String platform, String day) {
        String release = "40000000-0000-4000-8000-0000000389%02d".formatted(++releaseSequence);
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                release,
                game);
        jdbc.update(
                """
                INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status)
                SELECT publication_id, ?::uuid, ?::uuid, ?::uuid, '20000000-0000-4000-8000-000000000389', ?, ?::date, 'announced', 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                release,
                game,
                platform,
                day == null ? "unknown" : "day",
                day);
    }

    private void external(String game, String score, long count) {
        jdbc.update(
                """
                INSERT INTO catalogue.game_external_score(game_id,source_name,score_kind,score,score_count,source_url,observed_at,changed_at)
                VALUES(?::uuid,'IGDB','player',?::numeric,?,'https://www.igdb.com/games/platform','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')
                """,
                game,
                score,
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
