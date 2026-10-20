package com.videogameplatform.api.delivery.ratings;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.identity.domain.UserId;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL aggregation, validated principal, privacy headers and generated response contract. */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Import(PersonalRatingInsightsIntegrationTest.FixedClock.class)
class PersonalRatingInsightsIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("rating_insights");
    private static final String PATH = "/api/v1/me/rating-insights";
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final OpenApiResponseContract CONTRACT =
            OpenApiResponseContract.load("/me/rating-insights");
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MockMvc mvc;
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, true);
    }

    @BeforeEach
    void clean() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        jdbc.update("DELETE FROM ratings.rating");
        jdbc.update("DELETE FROM ratings.game_listing");
        jdbc.update("DELETE FROM ratings.genre_label");
        jdbc.update("DELETE FROM catalogue.game WHERE game_id::text LIKE 'aaaaaaaa-%'");
    }

    @Test
    void emptyAndOneGameNeverInventDataAndSerializeNulls() throws Exception {
        var empty = read("alice");
        assertThat(empty.path("total").longValue()).isZero();
        assertThat(empty.path("evaluatedOn").stringValue()).isEqualTo("2026-11-01");
        assertThat(empty.path("mean").isNull()).isTrue();
        assertThat(empty.path("highest").isNull()).isTrue();
        assertThat(empty.path("lowest").isNull()).isTrue();
        assertThat(empty.path("distribution").size()).isEqualTo(10);
        assertThat(empty.path("distribution"))
                .allSatisfy(count -> assertThat(count.longValue()).isZero());
        assertThat(empty.path("activity").size()).isEqualTo(12);
        assertThat(empty.path("activity").get(0).path("month").stringValue()).isEqualTo("2025-12");
        assertThat(empty.path("activity").get(11).path("month").stringValue()).isEqualTo("2026-11");
        assertThat(empty.path("bestRatedGenres").isEmpty()).isTrue();
        game(1, "alice", 7, "2026-10-31T23:30:00Z"); // November in Madrid, UTC still October.
        var single = read("alice");
        assertThat(single.path("total").longValue()).isEqualTo(1);
        assertThat(single.path("mean").decimalValue()).isEqualByComparingTo("7.0");
        assertThat(single.path("gamesWithoutGenres").longValue()).isEqualTo(1);
        assertThat(single.path("highest")).isEqualTo(single.path("lowest"));
        assertThat(single.path("activity").get(11).path("count").longValue()).isEqualTo(1);
        assertThat(single.path("activity").get(10).path("count").longValue()).isZero();
    }

    @Test
    void calculatesOverlappingGenresThresholdsUnroundedOrderTiesAndOnlyThePrincipal()
            throws Exception {
        for (int i = 1; i <= 5; i++)
            game(i, "alice", i <= 3 ? 9 : i == 4 ? 1 : 8, "2026-10-05T10:00:00Z");
        game(6, "bob", 10, "2026-10-05T10:00:00Z");
        genre("a", "Acción", 1, 2, 3);
        genre("b", "Aventura", 1, 2, 3);
        genre("c", "Rol", 1, 2, 4);
        genre("d", "Estrategia", 5, 6); // Bob must not affect Alice's sample or mean.
        var result = read("alice");
        assertThat(result.path("total").longValue()).isEqualTo(5);
        assertThat(result.path("mean").decimalValue()).isEqualByComparingTo("7.2");
        assertThat(result.path("distribution").get(8).longValue()).isEqualTo(3);
        assertThat(result.path("highest").path("tiedGames").longValue()).isEqualTo(3);
        assertThat(result.path("highest").path("game").path("gameId").stringValue())
                .isEqualTo(id(1).toString());
        assertThat(result.path("lowest").path("value").intValue()).isEqualTo(1);
        assertThat(result.path("mostRatedGenres").size()).isEqualTo(4);
        assertThat(
                        result.path("mostRatedGenres")
                                .get(0)
                                .path("genre")
                                .path("genreId")
                                .stringValue())
                .isEqualTo("a");
        assertThat(result.path("bestRatedGenres").size()).isEqualTo(3);
        assertThat(
                        result.path("bestRatedGenres")
                                .get(0)
                                .path("genre")
                                .path("genreId")
                                .stringValue())
                .isEqualTo("a");
        assertThat(
                        result.path("bestRatedGenres")
                                .get(1)
                                .path("genre")
                                .path("genreId")
                                .stringValue())
                .isEqualTo("b");
        assertThat(result.path("bestRatedGenres").get(2).path("mean").decimalValue())
                .isEqualByComparingTo("6.3");
        assertThat(read("bob").path("total").longValue()).isEqualTo(1);
        jdbc.update("UPDATE ratings.rating SET value=8,updated_at=now() WHERE game_id=?", id(1));
        assertThat(read("alice").path("mean").decimalValue()).isEqualByComparingTo("7.0");
        jdbc.update("DELETE FROM ratings.rating WHERE game_id=?", id(1));
        assertThat(read("alice").path("total").longValue()).isEqualTo(4);
        assertThat(read("alice").path("bestRatedGenres").isEmpty()).isTrue();
    }

    @Test
    void roundsHalfUpAndUsesCreationMonthsWithAnExactOlderBoundary() throws Exception {
        game(1, "alice", 8, "2025-11-30T23:00:00Z"); // Exactly December 1, Madrid.
        game(2, "alice", 9, "2025-11-30T22:59:59Z"); // Older than the twelve-month window.
        var result = read("alice");
        assertThat(result.path("mean").decimalValue()).isEqualByComparingTo("8.5");
        assertThat(result.path("olderRatings").longValue()).isEqualTo(1);
        assertThat(result.path("activity").get(0).path("count").longValue()).isEqualTo(1);
        jdbc.update("UPDATE ratings.rating SET value=10,updated_at=now() WHERE game_id=?", id(1));
        var edited = read("alice");
        assertThat(edited.path("mean").decimalValue()).isEqualByComparingTo("9.5");
        assertThat(edited.path("activity")).isEqualTo(result.path("activity"));
        jdbc.update("DELETE FROM ratings.rating WHERE game_id=?", id(1));
        assertThat(read("alice").path("activity").get(0).path("count").longValue()).isZero();
    }

    @Test
    void roundsAnExactHalfUpAndPreservesProjectedCoverProvenance() throws Exception {
        for (int i = 1; i <= 4; i++) game(i, "alice", i == 4 ? 8 : 7, "2026-10-05T10:00:00Z");
        jdbc.update(
                """
                UPDATE ratings.game_listing SET cover_kind='provider',
                    cover_reference='https://images.igdb.com/igdb/image/upload/t_cover_big_2x/example.webp',
                    cover_alternative_text='Game cover',cover_attribution='IGDB',
                    cover_source_url='https://www.igdb.com/games/example' WHERE game_id=?
                """,
                id(4));
        var result = read("alice");
        assertThat(result.path("mean").decimalValue()).isEqualByComparingTo("7.3");
        assertThat(
                        result.path("highest")
                                .path("game")
                                .path("primaryCover")
                                .path("kind")
                                .stringValue())
                .isEqualTo("provider");
        assertThat(
                        result.path("highest")
                                .path("game")
                                .path("primaryCover")
                                .path("attribution")
                                .path("label")
                                .stringValue())
                .isEqualTo("IGDB");
        assertThat(
                        result.path("lowest")
                                .path("game")
                                .path("primaryCover")
                                .path("kind")
                                .stringValue())
                .isEqualTo("fallback");
    }

    @Test
    void boundsAndOrdersGenreListsByExactMeanBeforeRounding() throws Exception {
        for (int i = 1; i <= 50; i++) game(i, "alice", i == 50 ? 9 : 8, "2026-10-05T10:00:00Z");
        for (int g = 0; g < 8; g++) {
            jdbc.update(
                    "INSERT INTO ratings.genre_label(genre_code,display_name) VALUES (?,?)",
                    "genre-" + g,
                    "Genre " + g);
            // All eight groups have the same count, but the final group includes the 9.
            for (int i = 1; i <= 21; i++)
                jdbc.update(
                        "INSERT INTO ratings.game_listing_genre(game_id,genre_code,position) VALUES (?,?,?)",
                        id(g == 7 && i == 21 ? 50 : i),
                        "genre-" + g,
                        g + 1);
        }
        var result = read("alice");
        assertThat(result.path("mostRatedGenres").size()).isEqualTo(6);
        assertThat(
                        result.path("mostRatedGenres")
                                .get(0)
                                .path("genre")
                                .path("genreId")
                                .stringValue())
                .isEqualTo("genre-0");
        // 169/21 rounds to 8.0, yet outranks 168/21. Never sort rounded display means.
        assertThat(result.path("bestRatedGenres").size()).isEqualTo(6);
        assertThat(
                        result.path("bestRatedGenres")
                                .get(0)
                                .path("genre")
                                .path("genreId")
                                .stringValue())
                .isEqualTo("genre-7");
        assertThat(result.path("bestRatedGenres").get(0).path("mean").decimalValue())
                .isEqualByComparingTo("8.0");
    }

    @Test
    void rejectsAnonymousAndBrowserIdentityAndClosesQueryParameters() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(PATH).with(login("alice")).param("userId", "bob"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAMETER_UNKNOWN"));
        mvc.perform(get(PATH).with(login("alice")).param("page", "1"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void failedAggregateReturnsSafePrivateProblemInsteadOfEmptyStatistics() throws Exception {
        jdbc.execute(
                "ALTER TABLE ratings.game_listing_genre RENAME TO insights_temporarily_unavailable");
        try {
            var response =
                    mvc.perform(get(PATH).with(login("alice")))
                            .andExpect(status().isInternalServerError())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.code").value("PERSONAL_RATINGS_READ_FAILED"))
                            .andReturn()
                            .getResponse();
            assertThat(response.getContentAsString())
                    .doesNotContain("insights_temporarily_unavailable", "SELECT", "jdbc");
        } finally {
            jdbc.execute(
                    "ALTER TABLE ratings.insights_temporarily_unavailable RENAME TO game_listing_genre");
        }
    }

    private JsonNode read(String subject) throws Exception {
        var response =
                mvc.perform(get(PATH).with(login(subject)))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().exists("X-Correlation-ID"))
                        .andReturn()
                        .getResponse();
        CONTRACT.assertJsonResponse(response, 200, "PersonalRatingInsights");
        return JSON.readTree(response.getContentAsString());
    }

    private void game(int n, String subject, int score, String createdAt) {
        jdbc.update("INSERT INTO catalogue.game(game_id,created_at) VALUES (?,now())", id(n));
        jdbc.update(
                "INSERT INTO ratings.game_listing(game_id,slug,canonical_title,normalized_title,cover_kind,genres_projected) VALUES (?,? ,? ,?,'unavailable',true)",
                id(n),
                "game-" + n,
                "Game " + n,
                "game " + n);
        jdbc.update(
                "INSERT INTO ratings.rating(user_id,game_id,value,created_at,updated_at) VALUES (?,?,?,?::timestamptz,'2026-11-02T00:00:00Z'::timestamptz)",
                UUID.fromString(UserId.fromIssuerAndSubject(ISSUER, subject).value()),
                id(n),
                score,
                createdAt);
    }

    private void genre(String code, String name, int... games) {
        jdbc.update(
                "INSERT INTO ratings.genre_label(genre_code,display_name) VALUES (?,?)",
                code,
                name);
        for (int game : games)
            jdbc.update(
                    "INSERT INTO ratings.game_listing_genre(game_id,genre_code,position) SELECT ?,?,coalesce(max(position),0)+1 FROM ratings.game_listing_genre WHERE game_id=?",
                    id(game),
                    code,
                    id(game));
    }

    private static UUID id(int n) {
        return UUID.fromString("aaaaaaaa-0000-4000-8000-" + String.format("%012d", n));
    }

    private static RequestPostProcessor login(String subject) {
        return oidcSession(
                token ->
                        token.issuer(ISSUER)
                                .subject(subject)
                                .issuedAt(Instant.parse("2026-10-31T23:29:00Z"))
                                .authTime(Instant.parse("2026-10-31T23:29:00Z"))
                                .expiresAt(Instant.parse("2026-11-01T00:30:00Z")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock insightsClock() {
            return Clock.fixed(Instant.parse("2026-10-31T23:30:00Z"), ZoneOffset.UTC);
        }
    }
}
