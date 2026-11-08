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
        jdbc.update("DELETE FROM ratings.platform_label WHERE platform_id::text LIKE 'bbbbbbbb-%'");
        jdbc.update("DELETE FROM catalogue.platform WHERE platform_id::text LIKE 'bbbbbbbb-%'");
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
        assertThat(empty.path("platforms").path("items").isEmpty()).isTrue();
        assertThat(empty.path("platforms").path("distribution"))
                .hasSize(5)
                .allSatisfy(count -> assertThat(count.longValue()).isZero());
        assertThat(empty.path("platforms").path("gamesWithoutPlatforms").longValue()).isZero();
        game(1, "alice", 7, "2026-10-31T23:30:00Z"); // November in Madrid, UTC still October.
        var single = read("alice");
        assertThat(single.path("total").longValue()).isEqualTo(1);
        assertThat(single.path("mean").decimalValue()).isEqualByComparingTo("7.0");
        assertThat(single.path("gamesWithoutGenres").longValue()).isEqualTo(1);
        assertThat(single.path("highest")).isEqualTo(single.path("lowest"));
        assertThat(single.path("activity").get(11).path("count").longValue()).isEqualTo(1);
        assertThat(single.path("activity").get(10).path("count").longValue()).isZero();
        // A direct rating without platforms stays valid and is never given one.
        assertThat(single.path("platforms").path("items").isEmpty()).isTrue();
        assertThat(single.path("platforms").path("distribution"))
                .allSatisfy(count -> assertThat(count.longValue()).isZero());
        assertThat(single.path("platforms").path("gamesWithoutPlatforms").longValue()).isEqualTo(1);
    }

    @Test
    void distributesEveryAssociationByProductIdentityBeyondTopSixAndOnlyForPrincipal()
            throws Exception {
        // Labels deliberately disagree with identities: presentation names cannot decide grouping.
        jdbc.update(
                "INSERT INTO ratings.platform_label(platform_id,display_name) "
                        + "SELECT platform_id,'Renamed platform' FROM catalogue.platform "
                        + "WHERE platform_id::text LIKE '10000000-%' ON CONFLICT DO NOTHING");
        for (int p = 1; p <= 7; p++) platform(p, p == 1 ? "PlayStation 5" : "Other " + p);
        game(1, "alice", 8, "2026-10-05T10:00:00Z");
        game(2, "alice", 7, "2026-10-05T10:00:00Z");
        game(3, "alice", 6, "2026-10-05T10:00:00Z"); // No platform: no slice.
        game(4, "bob", 1, "2026-10-05T10:00:00Z");
        // Seven others outrank each featured platform, including two others on the same game.
        for (int p = 1; p <= 7; p++) {
            played(1, "alice", p, null);
            played(2, "alice", p, null);
        }
        for (int p = 1; p <= 4; p++) {
            featuredPlayed(1, "alice", p);
            featuredPlayed(4, "bob", p);
        }
        var platforms = read("alice").path("platforms");
        assertThat(platforms.path("items"))
                .allSatisfy(item -> assertThat(item.path("playedGames").longValue()).isEqualTo(2));
        assertThat(platforms.path("otherPlatforms").longValue()).isEqualTo(5);
        assertThat(platforms.path("gamesWithoutPlatforms").longValue()).isEqualTo(1);
        var distribution = platforms.path("distribution");
        assertThat(distribution.path("ps5").longValue()).isEqualTo(1);
        assertThat(distribution.path("switch2").longValue()).isEqualTo(1);
        assertThat(distribution.path("xboxSeries").longValue()).isEqualTo(1);
        assertThat(distribution.path("windowsPc").longValue()).isEqualTo(1);
        assertThat(distribution.path("other").longValue()).isEqualTo(14);
        assertThat(distribution)
                .extracting(JsonNode::longValue)
                .containsExactly(1L, 1L, 1L, 1L, 14L);
        assertThat(read("bob").path("platforms").path("distribution").path("other").longValue())
                .isZero();
        // Removing a rating cascades its selections; another user's associations survive.
        jdbc.update(
                "DELETE FROM ratings.rating WHERE user_id=? AND game_id=?", user("alice"), id(1));
        var after = read("alice").path("platforms").path("distribution");
        assertThat(after.path("ps5").longValue()).isZero();
        assertThat(after.path("other").longValue()).isEqualTo(7);
        assertThat(read("bob").path("platforms").path("distribution").path("ps5").longValue())
                .isEqualTo(1);
    }

    private void featuredPlayed(int game, String subject, int platform) {
        jdbc.update(
                "INSERT INTO ratings.rating_platform(user_id,game_id,platform_id) VALUES (?,?,?)",
                user(subject),
                id(game),
                UUID.fromString("10000000-0000-4000-8000-" + String.format("%012d", platform)));
    }

    @Test
    void countsRecordedPlatformsWithOverlapAndOnlyExplicitPlatformScores() throws Exception {
        platform(1, "PlayStation 5");
        platform(2, "Nintendo Switch 2");
        // Game 1: a direct 9 played on both platforms without a score on either.
        game(1, "alice", 9, "2026-10-05T10:00:00Z");
        played(1, "alice", 1, null);
        played(1, "alice", 2, null);
        // Game 2: scored 10 and 8 by platform, contributing 9.
        game(2, "alice", 5, "2026-10-05T10:00:00Z");
        scoredByPlatform(2, "alice", 18, 2);
        played(2, "alice", 1, 10);
        played(2, "alice", 2, 8);
        // Game 3: scored 7 on one platform.
        game(3, "alice", 5, "2026-10-05T10:00:00Z");
        scoredByPlatform(3, "alice", 7, 1);
        played(3, "alice", 1, 7);
        // Game 4: a direct 5 without any platform.
        game(4, "alice", 5, "2026-10-05T10:00:00Z");
        // Bob's platform score must not reach Alice's readings.
        game(5, "bob", 5, "2026-10-05T10:00:00Z");
        scoredByPlatform(5, "bob", 1, 1);
        played(5, "bob", 1, 1);

        var result = read("alice");

        assertThat(result.path("mean").decimalValue()).isEqualByComparingTo("7.5");
        var platforms = result.path("platforms");
        assertThat(platforms.path("gamesWithoutPlatforms").longValue()).isEqualTo(1);
        assertThat(platforms.path("gamesOnSeveralPlatforms").longValue()).isEqualTo(2);
        assertThat(platforms.path("otherPlatforms").longValue()).isZero();
        assertThat(platforms.path("items").size()).isEqualTo(2);
        // These synthetic identities are Otras even when named PS5/Switch 2. Explicit scores
        // neither add units nor suppress played-only selections: 3 + 2 associations.
        assertThat(platforms.path("distribution").path("other").longValue()).isEqualTo(5);
        var first = platforms.path("items").get(0);
        assertThat(first.path("platform").path("name").stringValue()).isEqualTo("PlayStation 5");
        assertThat(first.path("platform").path("platformId").stringValue())
                .isEqualTo(platformId(1).toString());
        assertThat(first.path("playedGames").longValue()).isEqualTo(3);
        assertThat(first.path("scoredGames").longValue()).isEqualTo(2);
        // (10 + 7) / 2; game 1's direct 9 is never a PlayStation 5 score.
        assertThat(first.path("mean").decimalValue()).isEqualByComparingTo("8.5");
        var second = platforms.path("items").get(1);
        assertThat(second.path("playedGames").longValue()).isEqualTo(2);
        assertThat(second.path("scoredGames").longValue()).isEqualTo(1);
        assertThat(second.path("mean").decimalValue()).isEqualByComparingTo("8.0");

        jdbc.update("DELETE FROM ratings.rating WHERE game_id=?", id(2));
        var after = read("alice").path("platforms");
        assertThat(after.path("gamesOnSeveralPlatforms").longValue()).isEqualTo(1);
        assertThat(after.path("items").get(0).path("playedGames").longValue()).isEqualTo(2);
        assertThat(after.path("items").get(0).path("mean").decimalValue())
                .isEqualByComparingTo("7.0");
        // Played without a score: counted where played, with no mean at all.
        assertThat(after.path("items").get(1).path("scoredGames").longValue()).isZero();
        assertThat(after.path("items").get(1).path("mean").isNull()).isTrue();
        jdbc.update(
                "DELETE FROM ratings.rating_platform WHERE game_id=? AND platform_id=?",
                id(1),
                platformId(2));
        var cleared = read("alice").path("platforms");
        assertThat(cleared.path("items").size()).isEqualTo(1);
        assertThat(cleared.path("gamesOnSeveralPlatforms").longValue()).isZero();
        assertThat(cleared.path("gamesWithoutPlatforms").longValue()).isEqualTo(1);
    }

    @Test
    void boundsPlatformsToSixWithDeterministicTiesAndCountsTheRest() throws Exception {
        String[] names = {"Theta", "alpha", "Beta", "gamma", "Delta", "epsilon", "Zeta", "Eta"};
        for (int p = 1; p <= names.length; p++) platform(p, names[p - 1]);
        game(1, "alice", 5, "2026-10-05T10:00:00Z");
        game(2, "alice", 5, "2026-10-05T10:00:00Z");
        for (int p = 1; p <= names.length; p++) played(1, "alice", p, null);
        // Theta has a second game; Zeta has the same one game but a score of its own.
        played(2, "alice", 1, null);
        jdbc.update(
                "UPDATE ratings.rating SET value=NULL,platform_score_count=1,platform_score_sum=6"
                        + " WHERE game_id=?",
                id(1));
        jdbc.update(
                "UPDATE ratings.rating_platform SET value=6 WHERE game_id=? AND platform_id=?",
                id(1),
                platformId(7));

        var platforms = read("alice").path("platforms");

        assertThat(platforms.path("items").size()).isEqualTo(6);
        assertThat(platforms.path("otherPlatforms").longValue()).isEqualTo(2);
        assertThat(platforms.path("gamesOnSeveralPlatforms").longValue()).isEqualTo(1);
        assertThat(platforms.path("items"))
                .extracting(item -> item.path("platform").path("name").stringValue())
                .containsExactly("Theta", "Zeta", "alpha", "Beta", "Delta", "epsilon");
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
    void countsAPlatformDerivedGameOnceAtItsUnroundedContribution() throws Exception {
        game(1, "alice", 9, "2026-10-05T10:00:00Z");
        game(3, "alice", 8, "2026-10-05T10:00:00Z");
        // Game 2 is rated 10 on one platform and 9 on another: one game contributing 9.5.
        game(2, "alice", 5, "2026-10-05T10:00:00Z");
        UUID alice = UUID.fromString(UserId.fromIssuerAndSubject(ISSUER, "alice").value());
        jdbc.update(
                "INSERT INTO ratings.platform_label(platform_id,display_name) VALUES"
                        + " ('10000000-0000-4000-8000-000000000001','PlayStation 5'),"
                        + " ('10000000-0000-4000-8000-000000000002','Nintendo Switch 2')"
                        + " ON CONFLICT DO NOTHING");
        jdbc.update(
                "UPDATE ratings.rating SET value=NULL,platform_score_count=2,platform_score_sum=19"
                        + " WHERE user_id=? AND game_id=?",
                alice,
                id(2));
        jdbc.update(
                "INSERT INTO ratings.rating_platform(user_id,game_id,platform_id,value) VALUES"
                        + " (?,?,'10000000-0000-4000-8000-000000000001',10),"
                        + " (?,?,'10000000-0000-4000-8000-000000000002',9)",
                alice,
                id(2),
                alice,
                id(2));

        var result = read("alice");

        assertThat(result.path("total").longValue()).isEqualTo(3);
        // (9 + 9.5 + 8) / 3 = 8.83…, from the unrounded contributions.
        assertThat(result.path("mean").decimalValue()).isEqualByComparingTo("8.8");
        // Each game once: 9.5 in bucket 10, 9 in bucket 9 and 8 in bucket 8.
        assertThat(result.path("distribution").get(9).longValue()).isEqualTo(1);
        assertThat(result.path("distribution").get(8).longValue()).isEqualTo(1);
        assertThat(result.path("distribution").get(7).longValue()).isEqualTo(1);
        assertThat(result.path("highest").path("game").path("gameId").stringValue())
                .isEqualTo(id(2).toString());
        assertThat(result.path("highest").path("value").decimalValue()).isEqualByComparingTo("9.5");
        assertThat(result.path("highest").path("tiedGames").longValue()).isEqualTo(1);
        assertThat(result.path("lowest").path("value").decimalValue()).isEqualByComparingTo("8");
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

    private void platform(int n, String name) {
        jdbc.update(
                "INSERT INTO catalogue.platform(platform_id,code,display_name) VALUES (?,?,?)",
                platformId(n),
                "insights-platform-" + n,
                name);
        jdbc.update(
                "INSERT INTO ratings.platform_label(platform_id,display_name) VALUES (?,?)",
                platformId(n),
                name);
    }

    private void played(int game, String subject, int platform, Integer score) {
        jdbc.update(
                "INSERT INTO ratings.rating_platform(user_id,game_id,platform_id,value) VALUES (?,?,?,?)",
                user(subject),
                id(game),
                platformId(platform),
                score);
    }

    /** Turns a direct rating into one scored by platform, as a command writes it. */
    private void scoredByPlatform(int game, String subject, int sum, int count) {
        jdbc.update(
                "UPDATE ratings.rating SET value=NULL,platform_score_sum=?,platform_score_count=?"
                        + " WHERE user_id=? AND game_id=?",
                sum,
                count,
                user(subject),
                id(game));
    }

    private static UUID user(String subject) {
        return UUID.fromString(UserId.fromIssuerAndSubject(ISSUER, subject).value());
    }

    private static UUID platformId(int n) {
        return UUID.fromString("bbbbbbbb-0000-4000-8000-" + String.format("%012d", n));
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
