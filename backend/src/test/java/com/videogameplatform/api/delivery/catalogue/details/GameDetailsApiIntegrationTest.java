package com.videogameplatform.api.delivery.catalogue.details;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "catalogue.releases.freshness-threshold=P1D"})
@Import(GameDetailsApiIntegrationTest.FixedClock.class)
@Execution(ExecutionMode.SAME_THREAD)
class GameDetailsApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("game_details_api");
    private static final OpenApiResponseContract CONTRACT =
            OpenApiResponseContract.load("/games/{gameId}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired MeterRegistry metrics;
    private JdbcTemplate admin;
    private UUID game;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, true);
    }

    @BeforeEach
    void createGame() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        game = UUID.randomUUID();
        admin.update("INSERT INTO catalogue.game VALUES (?, now())", game);
        admin.update(
                """
            INSERT INTO catalogue.game_snapshot(publication_id, game_id, canonical_title, slug,
                cover_reference, cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status)
            SELECT publication_id, ?, 'Public game', ?, '/assets/covers/fallback.svg', 'Product',
                'product_owned', 'Cover unavailable', 'approved'
            FROM catalogue.catalogue_publication WHERE is_current
            """,
                game,
                "game-" + game);
        release("day", "released", "provider_only", "not_required");
    }

    private void release(String precision, String status, String verification, String review) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO catalogue.game_release VALUES (?, ?, now())", id, game);
        admin.update(
                """
            INSERT INTO catalogue.release_snapshot(publication_id, release_id, game_id, platform_id, region_id,
                date_precision, exact_date, release_year, release_month, release_quarter, release_status,
                source_kind, source_name, source_entity_type, last_synchronized_at, last_verified_at,
                verification_level, review_status)
            SELECT publication_id, ?, ?, '10000000-0000-4000-8000-000000000001',
                '20000000-0000-4000-8000-000000000002', ?,
                CASE WHEN ? = 'day' THEN date '2026-08-13' ELSE NULL END,
                CASE WHEN ? IN ('month', 'quarter', 'year') THEN 2026 ELSE NULL END,
                CASE WHEN ? = 'month' THEN 8 ELSE NULL END,
                CASE WHEN ? = 'quarter' THEN 3 ELSE NULL END, ?, 'official_source', 'Official source', 'release',
                timestamptz '2026-01-01 00:00:00Z',
                CASE WHEN ? = 'verified' THEN timestamptz '2026-01-01 00:00:00Z' ELSE NULL END, ?, ?
            FROM catalogue.catalogue_publication WHERE is_current
            """,
                id,
                game,
                precision,
                precision,
                precision,
                precision,
                precision,
                status,
                verification,
                verification,
                review);
    }

    @Test
    void servesCompletePublicEvidenceAndEmptyStatisticsWithConditionalCaching() throws Exception {
        var response = get(game.toString());
        CONTRACT.assertJsonResponse(response, 200, "GameDetails");
        var body = JSON.readTree(response.body());
        assertThat(body.path("ratingEligibility").path("eligible").asBoolean()).isTrue();
        assertThat(body.path("ratingEligibility").path("evaluatedOn").asString())
                .isEqualTo("2026-08-13");
        assertThat(body.path("ratingStatistics").path("count").asInt()).isZero();
        assertThat(body.path("ratingStatistics").path("mean").isNull()).isTrue();
        assertThat(body.path("ratingStatistics").path("distribution").properties())
                .hasSize(10)
                .allSatisfy(e -> assertThat(e.getValue().asInt()).isZero());
        var release = body.path("releases").get(0);
        assertThat(release.path("platform").path("platformId").asString())
                .isEqualTo("playstation-5");
        assertThat(release.path("region").path("regionId").asString()).isEqualTo("europe");
        assertThat(release.path("provenance").path("sourceKind").asString())
                .isEqualTo("official_source");
        assertThat(release.path("freshnessStatus").asString()).isEqualTo("stale");
        assertThat(body.has("personalRating")).isFalse();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        String etag = response.headers().firstValue("ETag").orElseThrow();
        CONTRACT.assertEmptyResponse(get(game.toString(), "If-None-Match", "W/" + etag), 304);
        assertThat(get(game.toString(), "Cookie", "JSESSIONID=untrusted").body())
                .isEqualTo(response.body());
        assertThat(
                        metrics.find("catalogue.game.details")
                                .tags(
                                        "eligibility",
                                        "ELIGIBLE_RELEASE_FOUND",
                                        "aggregate",
                                        "available")
                                .counter())
                .isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
        "month,released,provider_only,not_required,RELEASE_NOT_OCCURRED",
        "quarter,released,verified,not_required,RELEASE_NOT_OCCURRED",
        "year,released,verified,not_required,RELEASE_NOT_OCCURRED",
        "unknown,released,provider_only,not_required,RELEASE_DATE_UNCERTAIN",
        "unknown,released,verified,not_required,ELIGIBLE_RELEASE_FOUND",
        "day,released,verified,required,RELEASE_REVIEW_REQUIRED",
        "day,scheduled,verified,not_required,RELEASE_NOT_OCCURRED",
        "day,cancelled,verified,not_required,RELEASE_CANCELLED"
    })
    void preservesDatePrecisionAndEvaluatesReleaseEvidence(
            String precision, String status, String verification, String review, String reason)
            throws Exception {
        admin.update("DELETE FROM catalogue.release_snapshot WHERE game_id = ?", game);
        release(precision, status, verification, review);
        var response = get(game.toString());
        CONTRACT.assertJsonResponse(response, 200, "GameDetails");
        var body = JSON.readTree(response.body());
        assertThat(body.path("ratingEligibility").path("reason").asString()).isEqualTo(reason);
        assertThat(body.path("releases").get(0).path("releaseDate").path("precision").asString())
                .isEqualTo(precision);
        assertThat(body.path("releases").get(0).path("reviewStatus").asString()).isEqualTo(review);
    }

    @Test
    void missingOpaqueIdentifiersDoNotRevealProviderIdentity() throws Exception {
        for (String id : new String[] {UUID.randomUUID().toString(), "igdb-1234", "1-1-1-1-1"}) {
            var response = get(id);
            CONTRACT.assertJsonResponse(response, 404, "Problem");
            assertThat(JSON.readTree(response.body()).path("code").asString())
                    .isEqualTo("GAME_NOT_FOUND");
            assertThat(response.body()).doesNotContain(id);
        }
    }

    @Test
    void rejectsUnknownQueryInput() throws Exception {
        var response = get(game + "?evaluatedOn=2099-01-01");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(response.body()).path("code").asString())
                .isEqualTo("REQUEST_PARAMETER_UNKNOWN");
    }

    @Test
    void aggregatesOnlyThisGamesActiveRatingsAndInvalidatesTheValidator() throws Exception {
        String etag = get(game.toString()).headers().firstValue("ETag").orElseThrow();
        for (int value : new int[] {8, 9})
            admin.update(
                    "INSERT INTO ratings.rating(user_id, game_id, value) VALUES (?, ?, ?)",
                    UUID.randomUUID(),
                    game,
                    value);
        var response = get(game.toString(), "If-None-Match", etag);
        CONTRACT.assertJsonResponse(response, 200, "GameDetails");
        var statistics = JSON.readTree(response.body()).path("ratingStatistics");
        assertThat(statistics.path("count").asInt()).isEqualTo(2);
        assertThat(statistics.path("mean").decimalValue()).isEqualByComparingTo("8.5");
        assertThat(statistics.path("distribution").path("8").asInt()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                admin.update(
                                        "INSERT INTO ratings.rating(user_id, game_id, value) VALUES (?, ?, 11)",
                                        UUID.randomUUID(),
                                        game))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID user = UUID.randomUUID();
        admin.update(
                "INSERT INTO ratings.rating(user_id, game_id, value) VALUES (?, ?, 5)", user, game);
        assertThatThrownBy(
                        () ->
                                admin.update(
                                        "INSERT INTO ratings.rating(user_id, game_id, value) VALUES (?, ?, 6)",
                                        user,
                                        game))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void anAggregateDatabaseFailureDegradesTheGameWithoutInventingZeroRatings() throws Exception {
        admin.execute("ALTER TABLE ratings.rating RENAME TO unavailable_rating");
        try {
            var response = get(game.toString());
            CONTRACT.assertJsonResponse(response, 200, "GameDetails");
            var stats = JSON.readTree(response.body()).path("ratingStatistics");
            assertThat(stats.path("status").asString()).isEqualTo("unavailable");
            assertThat(stats.has("count")).isFalse();
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        } finally {
            admin.execute("ALTER TABLE ratings.unavailable_rating RENAME TO rating");
        }
    }

    @Test
    void noPublicationReturnsNotReadyInsteadOfProviderFallback() throws Exception {
        admin.update("UPDATE catalogue.catalogue_publication SET is_current = false");
        try {
            var response = get(game.toString());
            CONTRACT.assertJsonResponse(response, 503, "Problem");
            assertThat(JSON.readTree(response.body()).path("code").asString())
                    .isEqualTo("CATALOGUE_NOT_READY");
        } finally {
            admin.update(
                    "UPDATE catalogue.catalogue_publication SET is_current = true WHERE catalogue_version = 'prototype-catalogue-v1'");
        }
    }

    @Test
    void preservesApprovedAliasesSourcedSummaryAndCoverAttribution() throws Exception {
        admin.update(
                """
            UPDATE catalogue.game_snapshot SET summary_kind = 'sourced', summary_text = 'Source summary', summary_language = 'en',
                summary_source_kind = 'official_source', summary_source_name = 'Publisher', summary_source_entity_type = 'game_summary',
                cover_usage_mode = 'provider_cdn_reference', cover_reference = 'co1234', cover_source = 'IGDB', cover_source_url = 'https://www.igdb.com/games/example'
            WHERE game_id = ?
            """,
                game);
        for (String approval : new String[] {"approved", "pending", "rejected"}) {
            admin.update(
                    """
                INSERT INTO catalogue.game_alias(publication_id, game_id, alias, alias_kind, approval_status, source_kind, source_name)
                SELECT publication_id, ?, ?, 'alternative', ?, 'product_curated', 'Product' FROM catalogue.catalogue_publication WHERE is_current
                """,
                    game,
                    approval + " " + game,
                    approval);
        }
        var response = get(game.toString());
        CONTRACT.assertJsonResponse(response, 200, "GameDetails");
        var body = JSON.readTree(response.body());
        assertThat(body.path("aliases")).hasSize(1);
        assertThat(body.path("summary").path("provenance").path("sourceName").asString())
                .isEqualTo("Publisher");
        assertThat(body.path("primaryCover").path("attribution").path("label").asString())
                .isEqualTo("IGDB");
        assertThat(body.toString()).doesNotContain("providerId", "externalReference");
    }

    @Test
    void excessiveAliasesFailClosedInsteadOfTruncating() throws Exception {
        admin.update(
                """
            INSERT INTO catalogue.game_alias(publication_id, game_id, alias, alias_kind, approval_status, source_kind, source_name)
            SELECT publication_id, ?, ? || n::text, 'alternative', 'approved', 'product_curated', 'Product'
            FROM catalogue.catalogue_publication CROSS JOIN generate_series(1, 101) n WHERE is_current
            """,
                game,
                "Alias " + game + " ");
        assertThat(get(game.toString()).statusCode()).isEqualTo(500);
    }

    private HttpResponse<String> get(String id, String... headers) throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/v1/games/" + id))
                        .GET();
        if (headers.length > 0) request.headers(headers);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-08-12T22:00:00Z"), ZoneOffset.UTC);
        }
    }
}
