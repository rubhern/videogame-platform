package com.videogameplatform.api.delivery.catalogue.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.api.delivery.catalogue.search.GameSearchHttpProperties;
import com.videogameplatform.catalogue.application.releases.port.ExternalPlayerScoresReadPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.ratings.application.GetRatingSummariesUseCase;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

/**
 * Real PostgreSQL, contract, validators and module collaboration on the discovery reads and the
 * full catalogue search, which carry the same scores.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(DiscoveryScoresApiIntegrationTest.FixedClock.class)
@Execution(ExecutionMode.SAME_THREAD)
class DiscoveryScoresApiIntegrationTest {
    private static final String DB =
            PostgreSqlTestDatabase.isolatedDatabaseName("discovery_scores");
    private static final UUID GAME = UUID.fromString("30000000-0000-4000-8000-00000000000a");
    private static final UUID USER = UUID.fromString("70000000-0000-4000-8000-000000000255");
    private static final String FEATURED = "/api/v1/featured-releases?month=2026-10";
    private static final String UPCOMING =
            "/api/v1/releases?from=2026-10-01&to=2026-10-31&includeApproximateDates=true&pageSize=1";
    private static final String SEARCH = "/api/v1/games?q=crimson%20desert&pageSize=1";
    private static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private GetRatingSummariesUseCase ratings;
    @MockitoSpyBean private ExternalPlayerScoresReadPort external;
    @MockitoSpyBean private CatalogueProviderPort provider;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @BeforeEach
    void clearScores() {
        jdbc.update("DELETE FROM ratings.rating WHERE user_id=?", USER);
        jdbc.update("DELETE FROM catalogue.game_external_score WHERE game_id=?", GAME);
    }

    @AfterEach
    void clearScoresAndVerifyNoProviderCalls() {
        try {
            verify(provider, never()).fetchWorks(org.mockito.ArgumentMatchers.any());
        } finally {
            clearScores();
        }
    }

    @Test
    void keepsAbsenceCommunityOnlyExternalOnlyAndBothIncludingOneVoteOnEveryScoredContract()
            throws Exception {
        for (String path : new String[] {FEATURED, UPCOMING, SEARCH}) {
            var neither = response(path);
            assertThat(score(neither).path("community").path("count").asInt()).isZero();
            assertThat(score(neither).path("community").path("mean").isNull()).isTrue();
            assertThat(score(neither).path("external").isMissingNode()).isTrue();
            vote(9);
            var local = score(response(path));
            assertThat(local.path("community").path("count").asInt()).isEqualTo(1);
            assertThat(local.path("external").isMissingNode()).isTrue();
            externalScore("87.5", 1);
            var both = score(response(path));
            assertThat(both.path("community").path("mean").asDouble()).isEqualTo(9);
            assertThat(both.path("external").path("value").asDouble()).isEqualTo(8.8);
            assertThat(both.path("external").path("scale").asInt()).isEqualTo(10);
            assertThat(both.path("external").path("count").asLong()).isEqualTo(1);
            assertThat(both.path("external").path("observedAt").stringValue())
                    .isEqualTo("2020-01-01T00:00:00Z");
            assertThat(both.path("external").path("attribution").path("sourceUrl").stringValue())
                    .isEqualTo("https://www.igdb.com/games/crimson-desert");
            jdbc.update("DELETE FROM ratings.rating WHERE user_id=?", USER);
            assertThat(score(response(path)).path("external").path("value").asDouble())
                    .isEqualTo(8.8);
            clearScores();
        }
    }

    @Test
    void revalidatesAfterCommunityWritesAndExternalRefreshOrRemoval() throws Exception {
        for (String path : new String[] {FEATURED, UPCOMING, SEARCH}) {
            var current = response(path);
            String tag = current.headers().firstValue("ETag").orElseThrow();
            assertThat(get(path, tag).statusCode()).isEqualTo(304);
            vote(10);
            var changed = get(path, tag);
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(changed.headers().firstValue("ETag")).isNotEqualTo(Optional.of(tag));
            tag = changed.headers().firstValue("ETag").orElseThrow();
            externalScore("70", 1);
            changed = get(path, tag);
            assertThat(changed.statusCode()).isEqualTo(200);
            tag = changed.headers().firstValue("ETag").orElseThrow();
            externalScore("80", 2);
            changed = get(path, tag);
            assertThat(changed.statusCode()).isEqualTo(200);
            tag = changed.headers().firstValue("ETag").orElseThrow();
            jdbc.update(
                    "UPDATE catalogue.game_external_score SET score=NULL,score_count=NULL WHERE game_id=?",
                    GAME);
            changed = get(path, tag);
            assertThat(changed.statusCode()).isEqualTo(200);
            tag = changed.headers().firstValue("ETag").orElseThrow();
            jdbc.update("DELETE FROM ratings.rating WHERE user_id=?", USER);
            assertThat(get(path, tag).statusCode()).isEqualTo(200);
            clearScores();
        }
    }

    @Test
    void enrichesOnlySelectedPageAndKeepsUnknownCommunitySeparateFromRealExternalZero()
            throws Exception {
        externalScore("0", 1);
        doReturn(Map.of(GAME.toString(), Optional.<RatingStatistics.Summary>empty()))
                .when(ratings)
                .get(anySet());
        var read = response(UPCOMING);
        assertThat(score(read).path("community").path("status").stringValue())
                .isEqualTo("unavailable");
        assertThat(score(read).path("community").path("count").isMissingNode()).isTrue();
        assertThat(score(read).path("external").path("value").asDouble()).isZero();
        verify(ratings).get(Set.of(GAME.toString()));
        verify(external).read(Set.of(GAME.toString()));
    }

    @Test
    void searchEnrichesOnlyTheReturnedPageAndAnEmptyPageReadsNoScores() throws Exception {
        // "2" matches several games; the second page of one holds exactly one of them.
        var read = get("/api/v1/games?q=2&page=2&pageSize=1", null);
        OpenApiResponseContract.load("/games").assertJsonResponse(read, 200, "GameSearchPage");
        var body = JSON.readTree(read.body());
        assertThat(body.path("page").path("totalItems").asInt()).isGreaterThan(1);
        assertThat(body.path("items")).hasSize(1);
        String returned = body.path("items").get(0).path("gameId").stringValue();
        assertThat(
                        body.path("items")
                                .get(0)
                                .path("scores")
                                .path("community")
                                .path("status")
                                .stringValue())
                .isEqualTo("available");
        verify(ratings).get(Set.of(returned));
        verify(external).read(Set.of(returned));

        org.mockito.Mockito.clearInvocations(ratings, external);
        var beyond = get("/api/v1/games?q=2&page=99&pageSize=1", null);
        assertThat(JSON.readTree(beyond.body()).path("items")).isEmpty();
        verify(ratings, never()).get(anySet());
        verify(external, never()).read(anySet());
    }

    @Test
    void readsOneHundredSelectedIdentitiesWithExactlyTwoSqlQueries() {
        externalScore("75", 1);
        vote(7);
        var statements = new java.util.concurrent.atomic.AtomicInteger();
        // Count actual prepared SQL statements, rather than Spring's overloaded query methods.
        var dataSource =
                (javax.sql.DataSource)
                        java.lang.reflect.Proxy.newProxyInstance(
                                javax.sql.DataSource.class.getClassLoader(),
                                new Class<?>[] {javax.sql.DataSource.class},
                                (proxy, method, args) -> {
                                    Object result;
                                    try {
                                        result = method.invoke(jdbc.getDataSource(), args);
                                    } catch (
                                            java.lang.reflect.InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                    if (!method.getName().equals("getConnection")) return result;
                                    return java.lang.reflect.Proxy.newProxyInstance(
                                            java.sql.Connection.class.getClassLoader(),
                                            new Class<?>[] {java.sql.Connection.class},
                                            (connection, operation, values) -> {
                                                if (operation.getName().equals("prepareStatement"))
                                                    statements.incrementAndGet();
                                                try {
                                                    return operation.invoke(result, values);
                                                } catch (
                                                        java.lang.reflect.InvocationTargetException
                                                                exception) {
                                                    throw exception.getCause();
                                                }
                                            });
                                });
        var counted =
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource);
        var ids = new java.util.HashSet<String>();
        ids.add(GAME.toString());
        for (int i = 0; i < 99; i++)
            ids.add(
                    UUID.nameUUIDFromBytes(
                                    ("score-batch-" + i)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .toString());
        var externalBatch =
                new com.videogameplatform.catalogue.adapter.persistence.releases
                                .JdbcExternalPlayerScoresReadAdapter(counted)
                        .read(ids);
        var communityBatch =
                new com.videogameplatform.ratings.adapter.persistence
                                .JdbcRatingSummariesReadAdapter(counted)
                        .read(ids);
        assertThat(externalBatch).containsOnlyKeys(GAME.toString());
        assertThat(communityBatch).hasSize(100);
        assertThat(communityBatch.get(GAME.toString()))
                .hasValueSatisfying(
                        summary -> {
                            assertThat(summary.count()).isEqualTo(1);
                            assertThat(summary.mean()).isEqualByComparingTo("7");
                        });
        assertThat(statements.get()).isEqualTo(2);
    }

    @Test
    void legacyDeploymentFreshnessCannotBypassScoreRevalidation() {
        assertThat(
                        new ReleaseHttpProperties("public, max-age=60, stale-while-revalidate=300")
                                .cacheControl())
                .isEqualTo("public, no-cache");
        assertThat(
                        new GameSearchHttpProperties(
                                        "public, max-age=60, stale-while-revalidate=300")
                                .cacheControl())
                .isEqualTo("public, no-cache");
    }

    private void vote(int value) {
        jdbc.update(
                "INSERT INTO ratings.rating(user_id,game_id,value,created_at,updated_at) VALUES(?,?,?,now(),now())",
                USER,
                GAME,
                value);
    }

    private void externalScore(String value, long count) {
        jdbc.update(
                """
                INSERT INTO catalogue.game_external_score(game_id,source_name,score_kind,score,score_count,source_url,observed_at,changed_at)
                VALUES(?,'IGDB','player',?::numeric,?,'https://www.igdb.com/games/crimson-desert','2020-01-01T00:00:00Z','2020-01-01T00:00:00Z')
                ON CONFLICT(game_id,source_name,score_kind) DO UPDATE SET score=excluded.score,score_count=excluded.score_count
                """,
                GAME,
                value,
                count);
    }

    private HttpResponse<String> response(String path) throws Exception {
        var response = get(path, null);
        if (path.equals(FEATURED)) {
            OpenApiResponseContract.load("/featured-releases")
                    .assertJsonResponse(response, 200, "FeaturedReleases");
        } else if (path.equals(SEARCH)) {
            OpenApiResponseContract.load("/games")
                    .assertJsonResponse(response, 200, "GameSearchPage");
        } else {
            OpenApiResponseContract.load("/releases")
                    .assertJsonResponse(response, 200, "ReleasePage");
        }
        assertThat(response.headers().firstValue("Cache-Control")).contains("public, no-cache");
        return response;
    }

    private JsonNode score(HttpResponse<String> response) throws Exception {
        var items = JSON.readTree(response.body()).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("gameId").stringValue()).isEqualTo(GAME.toString());
        return items.get(0).path("scores");
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
