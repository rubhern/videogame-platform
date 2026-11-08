package com.videogameplatform.api.delivery.catalogue.release;

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

/** UC-020 over HTTP: contract, ranking, horizon, empty states, validators and closed parameters. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(UpcomingHighlightsApiIntegrationTest.FixedClock.class)
@Execution(ExecutionMode.SAME_THREAD)
class UpcomingHighlightsApiIntegrationTest {
    private static final String DB =
            PostgreSqlTestDatabase.isolatedDatabaseName("upcoming_highlights_api");
    private static final String PLATFORM = "10000000-0000-4000-8000-000000000388";
    private static final String QUIET_PLATFORM = "10000000-0000-4000-8000-000000000387";
    private static final String PATH = "/api/v1/platform-upcoming-highlights?platformIds=";
    private static final String ANTICIPATED = "30000000-0000-4000-8000-000000000388";
    private static final String PORT = "30000000-0000-4000-8000-000000000387";
    private static final String UNHYPED = "30000000-0000-4000-8000-000000000386";
    private static final String OUT = "30000000-0000-4000-8000-000000000385";
    private static final List<String> GAMES = List.of(ANTICIPATED, PORT, UNHYPED, OUT);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenApiResponseContract CONTRACT =
            OpenApiResponseContract.load("/platform-upcoming-highlights");

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private CatalogueProviderPort provider;
    private int releaseSequence;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES ('20000000-0000-4000-8000-000000000388', 'upcoming-highlights-api', 'Mundial') ON CONFLICT DO NOTHING");
        jdbc.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES (?::uuid, 'upcoming-highlights-api', 'Anticipation Station'), (?::uuid, 'upcoming-highlights-quiet', 'Quiet Station') ON CONFLICT DO NOTHING",
                PLATFORM,
                QUIET_PLATFORM);
        // Evaluated on 27 October 2026 in Madrid: the horizon ends on 26 April 2027.
        game(ANTICIPATED, "Most Anticipated", 1, 500L);
        release(ANTICIPATED, PLATFORM, "day", "2027-02-12", null);
        // An older game still to come on this platform: a port qualifies.
        game(PORT, "Platform Port", 2, 120L);
        release(PORT, QUIET_PLATFORM, "day", "2019-05-01", null);
        release(PORT, PLATFORM, "quarter", null, 1);
        game(UNHYPED, "Without Attention", 3, null);
        release(UNHYPED, PLATFORM, "day", "2026-12-01", null);
        // Already out on the platform: never upcoming there, however much attention it has.
        game(OUT, "Already Out", 4, 900L);
        release(OUT, PLATFORM, "day", "2026-10-20", null);
        release(OUT, PLATFORM, "day", "2027-01-15", null);
    }

    @AfterEach
    void clear() {
        try {
            verify(provider, never()).fetchWorks(org.mockito.ArgumentMatchers.any());
        } finally {
            for (String game : GAMES) {
                jdbc.update(
                        "DELETE FROM catalogue.game_featured_evidence WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.release_snapshot WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_release WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game_snapshot WHERE game_id=?::uuid", game);
                jdbc.update("DELETE FROM catalogue.game WHERE game_id=?::uuid", game);
            }
        }
    }

    @Test
    void ranksThePlatformsAnticipatedGamesInsideTheMadridHorizon() throws Exception {
        var response = get(PATH + PLATFORM, null);
        CONTRACT.assertJsonResponse(response, 200, "PlatformUpcomingHighlights");
        assertThat(response.headers().firstValue("Cache-Control")).isPresent();
        JsonNode body = JSON.readTree(response.body());

        assertThat(body.path("evaluatedOn").stringValue()).isEqualTo("2026-10-27");
        assertThat(body.path("window").path("from").stringValue()).isEqualTo("2026-10-27");
        assertThat(body.path("window").path("to").stringValue()).isEqualTo("2027-04-26");
        assertThat(body.path("platforms").get(0).path("name").stringValue())
                .isEqualTo("Anticipation Station");
        assertThat(body.path("selection").path("status").stringValue()).isEqualTo("ranked");
        assertThat(ids(body)).containsExactly(ANTICIPATED, PORT);

        JsonNode first = body.path("items").get(0);
        assertThat(first.path("position").asInt()).isEqualTo(1);
        assertThat(first.path("featuredImage").path("kind").stringValue()).isEqualTo("fallback");
        JsonNode second = body.path("items").get(1);
        assertThat(second.path("position").asInt()).isEqualTo(2);
        // The platform's own release, at its real precision; never the game's first release.
        assertThat(second.path("platformRelease").path("platform").path("platformId").stringValue())
                .isEqualTo(PLATFORM);
        assertThat(
                        second.path("platformRelease")
                                .path("releaseDate")
                                .path("precision")
                                .stringValue())
                .isEqualTo("quarter");
        assertThat(second.path("platformRelease").path("releaseDate").path("value").stringValue())
                .isEqualTo("2027-Q1");
        assertThat(second.path("platformRelease").path("status").stringValue())
                .isEqualTo("scheduled");
        // Hypes are attention, never exposed as a value.
        assertThat(first.has("hypes")).isFalse();
    }

    @Test
    void revalidatesAStoredRepresentationByItsEntityTag() throws Exception {
        var first = get(PATH + PLATFORM, null);
        String tag = first.headers().firstValue("ETag").orElseThrow();

        assertThat(get(PATH + PLATFORM, tag).statusCode()).isEqualTo(304);
    }

    @Test
    void reportsAScopeWithoutUpcomingGamesAsEmptyAndOneWithoutHypesAsUnranked() throws Exception {
        var quiet = get(PATH + QUIET_PLATFORM, null);
        CONTRACT.assertJsonResponse(quiet, 200, "PlatformUpcomingHighlights");
        assertThat(JSON.readTree(quiet.body()).path("selection").path("status").stringValue())
                .isEqualTo("no_qualifying_releases");

        jdbc.update(
                "UPDATE catalogue.game_featured_evidence SET hypes = NULL WHERE game_id IN (?::uuid, ?::uuid)",
                ANTICIPATED,
                PORT);
        var unranked = get(PATH + PLATFORM, null);
        CONTRACT.assertJsonResponse(unranked, 200, "PlatformUpcomingHighlights");
        JsonNode body = JSON.readTree(unranked.body());
        assertThat(body.path("selection").path("status").stringValue())
                .isEqualTo("popularity_unavailable");
        assertThat(body.path("items")).isEmpty();
    }

    @Test
    void aFamilyCombinesItsPlatforms() throws Exception {
        var family = get(PATH + PLATFORM + "&platformIds=" + QUIET_PLATFORM, null);
        CONTRACT.assertJsonResponse(family, 200, "PlatformUpcomingHighlights");
        JsonNode body = JSON.readTree(family.body());

        assertThat(body.path("platforms")).hasSize(2);
        // The port is already out on the quiet platform, so it is no longer new to the family.
        assertThat(ids(body)).containsExactly(ANTICIPATED);
    }

    @Test
    void rejectsAMissingOrUnknownScopeAndUnknownParameters() throws Exception {
        var missing = get("/api/v1/platform-upcoming-highlights", null);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(missing.body()).path("code").stringValue())
                .isEqualTo("REQUEST_MALFORMED");
        assertProblem(get(PATH + "platform_invented", null), "PLATFORM_NOT_SUPPORTED");
        assertProblem(get(PATH + PLATFORM + "&month=2026-11", null), "REQUEST_PARAMETER_UNKNOWN");
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

    private void game(String id, String title, int number, Long hypes) {
        jdbc.update("INSERT INTO catalogue.game (game_id, created_at) VALUES (?::uuid, now())", id);
        jdbc.update(
                """
                INSERT INTO catalogue.game_snapshot
                    (publication_id, game_id, canonical_title, slug, cover_reference, cover_source,
                     cover_usage_mode, cover_alternative_text, cover_usage_status)
                SELECT publication_id, ?::uuid, ?, ?, '/assets/covers/fallback.svg', 'VideoGame Platform',
                       'product_owned', 'Upcoming highlights fixture', 'approved'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                id,
                title,
                "upcoming-highlights-api-" + number);
        jdbc.update(
                """
                INSERT INTO catalogue.game_featured_evidence
                    (game_id, hypes, first_release_date, eligible_product, source_name, observed_at)
                VALUES (?::uuid, ?, NULL, true, 'IGDB', '2026-10-26T05:00:00Z')
                """,
                id,
                hypes);
    }

    private void release(
            String game, String platform, String precision, String day, Integer quarter) {
        String release = "40000000-0000-4000-8000-0000000388%02d".formatted(++releaseSequence);
        jdbc.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                release,
                game);
        jdbc.update(
                """
                INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_year, release_quarter, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status, release_stage)
                SELECT publication_id, ?::uuid, ?::uuid, ?::uuid, '20000000-0000-4000-8000-000000000388', ?, ?::date, ?, ?, 'announced', 'external_provider', 'IGDB', 'release_date', now(), 'provider_only', 'not_required', 'full_release'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                release,
                game,
                platform,
                precision,
                day,
                quarter == null ? null : 2027,
                quarter);
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
            return Clock.fixed(Instant.parse("2026-10-26T23:30:00Z"), ZoneId.of("Europe/Madrid"));
        }
    }
}
