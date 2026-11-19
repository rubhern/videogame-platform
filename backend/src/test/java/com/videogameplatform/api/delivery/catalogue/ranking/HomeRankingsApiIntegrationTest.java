package com.videogameplatform.api.delivery.catalogue.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.adapter.persistence.details.JdbcGameDetailVisits;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(HomeRankingsApiIntegrationTest.FixedClock.class)
class HomeRankingsApiIntegrationTest {
    private static final String DB = PostgreSqlTestDatabase.isolatedDatabaseName("home_rankings");
    private static final String ONE = "30000000-0000-4000-8000-000000000001";
    private static final String TWO = "30000000-0000-4000-8000-000000000002";
    private static final String THREE = "30000000-0000-4000-8000-000000000003";
    private static final String NOW = "2026-11-19T10:00:00Z";
    private static final String START = "2026-11-12T10:00:00Z";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcGameDetailVisits visits;
    @LocalServerPort private int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @Test
    void ranksPositiveActivityWithinEachWindowAndBreaksTiesByGame() throws Exception {
        addRating(ONE, START, NOW);
        addRating(TWO, "2026-11-13T10:00:00Z", NOW);
        addRating(THREE, "2026-11-12T09:59:59Z", NOW);
        addRating(THREE, NOW, NOW);
        addVisit(TWO, START);
        addVisit(TWO, "2026-11-13T10:00:00Z");
        addVisit(ONE, "2026-11-18T10:00:00Z");
        addVisit(THREE, "2026-11-12T09:59:59Z");

        assertThat(ranking("votes")).containsExactly(ONE + ":1", TWO + ":1");
        assertThat(ranking("visits")).containsExactly(TWO + ":2", ONE + ":1");
    }

    @Test
    void acceptsAnonymousVisitOnceAndRejectsCrossOriginAndCrawlers() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(post(id, "https://unrelated.example", "Browser").statusCode()).isEqualTo(403);
        assertThat(post(id, null, "ExampleCrawler").statusCode()).isEqualTo(204);
        assertThat(stored(id)).isZero();

        var success = post(id, "http://localhost:" + port, "Browser");
        assertThat(success.statusCode()).isEqualTo(204);
        assertThat(success.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(post(id, null, "Browser").statusCode()).isEqualTo(204);
        assertThat(stored(id)).isEqualTo(1);
    }

    @Test
    void purgesVisitsOlderThanEightDays() {
        String expired = UUID.randomUUID().toString();
        String retained = UUID.randomUUID().toString();
        for (String id : List.of(expired, retained)) {
            jdbc.update(
                    """
                    INSERT INTO catalogue.game_detail_visit (visit_id, game_id, occurred_at)
                    VALUES (?::uuid, ?::uuid, ?::timestamptz)
                    """,
                    id,
                    ONE,
                    id.equals(expired) ? "2026-11-11T09:59:59Z" : "2026-11-11T10:00:00Z");
        }
        visits.purgeExpiredBatch();
        assertThat(stored(expired)).isZero();
        assertThat(stored(retained)).isEqualTo(1);
    }

    private void addRating(String gameId, String created, String updated) {
        jdbc.update(
                """
                INSERT INTO ratings.rating (user_id, game_id, value, created_at, updated_at)
                VALUES (gen_random_uuid(), ?::uuid, 7, ?::timestamptz, ?::timestamptz)
                """,
                gameId,
                created,
                updated);
    }

    private void addVisit(String gameId, String time) {
        jdbc.update(
                """
                INSERT INTO catalogue.game_detail_visit (visit_id, game_id, occurred_at)
                VALUES (gen_random_uuid(), ?::uuid, ?::timestamptz)
                """,
                gameId,
                time);
    }

    private int stored(String id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM catalogue.game_detail_visit WHERE visit_id = ?::uuid",
                Integer.class,
                id);
    }

    private List<String> ranking(String kind) throws Exception {
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(uri("/home-rankings/" + kind)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return new ObjectMapper()
                .readTree(response.body())
                .path("items")
                .valueStream()
                .map(item -> item.path("gameId").stringValue() + ":" + item.path("count").asLong())
                .toList();
    }

    private HttpResponse<String> post(String id, String origin, String agent) throws Exception {
        var request =
                HttpRequest.newBuilder(uri("/game-detail-visits"))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", agent);
        if (origin != null) request.header("Origin", origin);
        var body =
                """
                {"gameId":"%s","visitId":"%s"}
                """
                        .formatted(ONE, id);
        return HttpClient.newHttpClient()
                .send(
                        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/api/v1" + path);
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC);
        }
    }
}
