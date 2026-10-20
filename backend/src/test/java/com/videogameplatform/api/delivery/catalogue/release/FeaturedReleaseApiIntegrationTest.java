package com.videogameplatform.api.delivery.catalogue.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP evidence for UC-010 against the deterministic seed on the pinned browser-gate clock. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(FeaturedReleaseApiIntegrationTest.FixedClockConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class FeaturedReleaseApiIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("featured_api");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OpenApiResponseContract OPENAPI =
            OpenApiResponseContract.load("/featured-releases");

    @LocalServerPort private int port;

    @Autowired private MeterRegistry meterRegistry;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort
            provider;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.videogameplatform.catalogue.application.localization.port.CatalogueTranslationPort
            translator;

    @org.junit.jupiter.api.AfterEach
    void publicReadsNeverAcquireProviderData() {
        org.mockito.Mockito.verify(translator, org.mockito.Mockito.never())
                .translate(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never())
                .fetchWorks(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never())
                .logos(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never())
                .releaseGames(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                                org.mockito.ArgumentMatchers.anyInt());
    }

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE_NAME, true);
    }

    @Test
    void featuresTheCurrentMadridMonthByDefaultWithContractHeadersAndConditionalRead()
            throws Exception {
        HttpResponse<String> response = get("/api/v1/featured-releases");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());

        OPENAPI.assertJsonResponse(response, 200, "FeaturedReleases");
        // Without parameters the month stays the default, as before weeks existed.
        assertThat(body.path("granularity").stringValue()).isEqualTo("month");
        assertThat(body.path("month").stringValue()).isEqualTo("2026-08");
        assertThat(body.has("week")).isFalse();
        assertThat(body.path("evaluatedOn").stringValue()).isEqualTo("2026-08-13");
        assertThat(body.path("window").path("from").stringValue()).isEqualTo("2026-08-01");
        assertThat(body.path("window").path("to").stringValue()).isEqualTo("2026-08-31");
        assertThat(body.path("navigation").path("previous").stringValue()).isEqualTo("2026-07");
        assertThat(body.path("navigation").path("next").stringValue()).isEqualTo("2026-09");
        assertThat(json(get("/api/v1/featured-releases?granularity=month"))).isEqualTo(body);
        // The seed holds no qualifying release in August: an explicit state, not an error.
        assertThat(body.path("selection").path("status").stringValue())
                .isEqualTo("no_qualifying_releases");
        assertThat(body.path("selection").has("popularityFreshness")).isFalse();
        assertThat(body.path("items")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("public, no-cache");
        String entityTag = response.headers().firstValue("ETag").orElseThrow();
        assertThat(entityTag).matches("\"[0-9a-f]{64}\"");

        HttpResponse<String> notModified =
                get("/api/v1/featured-releases", "If-None-Match", entityTag);
        OPENAPI.assertEmptyResponse(notModified, 304);
        assertThat(get("/api/v1/featured-releases?month=2026-10").headers().firstValue("ETag"))
                .isNotEqualTo(java.util.Optional.of(entityTag));
        assertThat(get("/api/v1/featured-releases?granularity=week").headers().firstValue("ETag"))
                .isNotEqualTo(java.util.Optional.of(entityTag));
    }

    @Test
    void featuresTheCurrentMadridIsoWeekFromMondayToSunday() throws Exception {
        // Thursday 13 August 2026 belongs to ISO week 33, Monday 10 to Sunday 16 August.
        HttpResponse<String> response = get("/api/v1/featured-releases?granularity=week");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());

        OPENAPI.assertJsonResponse(response, 200, "FeaturedReleases");
        assertThat(body.path("granularity").stringValue()).isEqualTo("week");
        assertThat(body.path("week").stringValue()).isEqualTo("2026-W33");
        assertThat(body.has("month")).isFalse();
        assertThat(body.path("evaluatedOn").stringValue()).isEqualTo("2026-08-13");
        assertThat(body.path("window").path("from").stringValue()).isEqualTo("2026-08-10");
        assertThat(body.path("window").path("to").stringValue()).isEqualTo("2026-08-16");
        assertThat(body.path("navigation").path("previous").stringValue()).isEqualTo("2026-W32");
        assertThat(body.path("navigation").path("next").stringValue()).isEqualTo("2026-W34");
        assertThat(body.path("selection").path("status").stringValue())
                .isEqualTo("no_qualifying_releases");
        assertThat(response.headers().firstValue("Cache-Control")).contains("public, no-cache");
        String entityTag = response.headers().firstValue("ETag").orElseThrow();
        OPENAPI.assertEmptyResponse(
                get("/api/v1/featured-releases?granularity=week", "If-None-Match", entityTag), 304);
        // Naming the current week is the same representation.
        assertThat(json(get("/api/v1/featured-releases?week=2026-W33"))).isEqualTo(body);
        assertThat(json(get("/api/v1/featured-releases?granularity=week&week=2026-W33")))
                .isEqualTo(body);
    }

    @Test
    void aWeekRanksOnlyGamesWhoseOwnFirstReleaseFallsInsideIt() throws Exception {
        var wolverine = java.util.UUID.fromString("30000000-0000-4000-8000-00000000000c");
        // The seed dates Wolverine's first release on 15 September and its only release on Friday
        // 25 September: September features it, but no week genuinely holds both.
        JsonNode before = json(get("/api/v1/featured-releases?week=2026-W39"));
        assertThat(before.path("window").path("from").stringValue()).isEqualTo("2026-09-21");
        assertThat(before.path("window").path("to").stringValue()).isEqualTo("2026-09-27");
        assertThat(before.path("selection").path("status").stringValue())
                .isEqualTo("no_qualifying_releases");
        assertThat(
                        json(get("/api/v1/featured-releases?week=2026-W38"))
                                .path("selection")
                                .path("status")
                                .stringValue())
                .isEqualTo("no_qualifying_releases");
        try {
            jdbc.update(
                    "UPDATE catalogue.game_featured_evidence SET first_release_date=DATE '2026-09-25' WHERE game_id=?",
                    wolverine);
            HttpResponse<String> response = get("/api/v1/featured-releases?week=2026-W39");
            JsonNode body = OBJECT_MAPPER.readTree(response.body());

            OPENAPI.assertJsonResponse(response, 200, "FeaturedReleases");
            assertThat(body.path("selection").path("status").stringValue()).isEqualTo("ranked");
            assertThat(ids(body.path("items"), "canonicalTitle"))
                    .containsExactly("Marvel's Wolverine");
            JsonNode date = body.path("items").get(0).path("releases").get(0).path("releaseDate");
            assertThat(date.path("precision").stringValue()).isEqualTo("day");
            assertThat(date.path("value").stringValue()).isEqualTo("2026-09-25");
            // Crimson Desert's October is month precision: no week of October may claim it.
            for (String week :
                    List.of("2026-W40", "2026-W41", "2026-W42", "2026-W43", "2026-W44")) {
                assertThat(json(get("/api/v1/featured-releases?week=" + week)).path("items"))
                        .isEmpty();
            }
        } finally {
            jdbc.update(
                    "UPDATE catalogue.game_featured_evidence SET first_release_date=DATE '2026-09-15' WHERE game_id=?",
                    wolverine);
        }
    }

    @Test
    void navigatesSixteenWeeksAndTenMonthsBackAndTheAcquisitionHorizonForward() throws Exception {
        // 13 August plus the default 180-day horizon is 9 February 2027: January is the last month
        // wholly inside it.
        JsonNode firstWeek = json(get("/api/v1/featured-releases?week=2026-W17"));
        assertThat(firstWeek.path("navigation").has("previous")).isFalse();
        assertThat(firstWeek.path("navigation").path("next").stringValue()).isEqualTo("2026-W18");
        JsonNode lastWeek = json(get("/api/v1/featured-releases?week=2026-W49"));
        assertThat(lastWeek.path("window").path("to").stringValue()).isEqualTo("2026-12-06");
        assertThat(lastWeek.path("navigation").path("previous").stringValue())
                .isEqualTo("2026-W48");
        assertThat(lastWeek.path("navigation").has("next")).isFalse();
        JsonNode firstMonth = json(get("/api/v1/featured-releases?month=2025-10"));
        assertThat(firstMonth.path("navigation").has("previous")).isFalse();
        assertThat(firstMonth.path("navigation").path("next").stringValue()).isEqualTo("2025-11");
        JsonNode lastMonth = json(get("/api/v1/featured-releases?month=2027-01"));
        assertThat(lastMonth.path("navigation").path("previous").stringValue())
                .isEqualTo("2026-12");
        assertThat(lastMonth.path("navigation").has("next")).isFalse();
        OPENAPI.assertJsonResponse(
                get("/api/v1/featured-releases?month=2027-01"), 200, "FeaturedReleases");

        for (String outside : List.of("week=2026-W16", "week=2026-W50", "week=2019-W10")) {
            assertPointer(get("/api/v1/featured-releases?" + outside), "/query/week");
        }
        for (String outside : List.of("month=2025-09", "month=2027-02")) {
            assertPointer(get("/api/v1/featured-releases?" + outside), "/query/month");
        }
    }

    @Test
    void ranksAnotherMonthFromLocalPopularityAndPresentsItsQualifyingReleases() throws Exception {
        HttpResponse<String> october = get("/api/v1/featured-releases?month=2026-10");
        JsonNode body = OBJECT_MAPPER.readTree(october.body());

        OPENAPI.assertJsonResponse(october, 200, "FeaturedReleases");
        assertThat(body.path("month").stringValue()).isEqualTo("2026-10");
        assertThat(body.path("evaluatedOn").stringValue()).isEqualTo("2026-08-13");
        assertThat(body.path("selection").path("status").stringValue()).isEqualTo("ranked");
        assertThat(body.path("selection").path("popularityFreshness").stringValue())
                .isEqualTo("fresh");
        assertThat(body.path("selection").path("popularityObservedAt").stringValue())
                .isEqualTo("2026-08-09T10:00:00Z");
        assertThat(ids(body.path("items"), "canonicalTitle")).containsExactly("Crimson Desert");
        JsonNode releases = body.path("items").get(0).path("releases");
        assertThat(releases).hasSize(2);
        releases.forEach(
                release -> {
                    assertThat(release.path("releaseDate").path("precision").stringValue())
                            .isEqualTo("month");
                    assertThat(release.path("releaseDate").path("value").stringValue())
                            .isEqualTo("2026-10");
                });
        // The signal value itself never reaches the browser.
        assertThat(october.body())
                .doesNotContain("visits", "hypes", "version_parent", "first_release_date");
        // The seed stores no provider media and only a product cover: the product-owned landscape
        // fallback fills the frame, and the canonical title stays without a logo.
        JsonNode image = body.path("items").get(0).path("featuredImage");
        assertThat(image.path("kind").stringValue()).isEqualTo("fallback");
        assertThat(image.path("presentation").stringValue()).isEqualTo("fill");
        assertThat(image.path("url").stringValue()).isEqualTo("/assets/featured/fallback.svg");
        assertThat(image.path("attribution").isNull()).isTrue();
        assertThat(body.path("items").get(0).has("logo")).isFalse();

        assertThat(
                        ids(
                                json(get("/api/v1/featured-releases?month=2026-09")).path("items"),
                                "canonicalTitle"))
                .containsExactly("Marvel's Wolverine");
        // Both ends of the current year are selectable; December has no qualifying seed release.
        OPENAPI.assertJsonResponse(
                get("/api/v1/featured-releases?month=2026-01"), 200, "FeaturedReleases");
        JsonNode december = json(get("/api/v1/featured-releases?month=2026-12"));
        assertThat(december.path("selection").path("status").stringValue())
                .isEqualTo("no_qualifying_releases");
        assertThat(december.path("items")).isEmpty();
    }

    @Test
    void restrictsTheRankingToExplicitPlatformsWithoutAnySessionInput() throws Exception {
        JsonNode october = json(get("/api/v1/featured-releases?month=2026-10"));
        List<String> presented = new java.util.ArrayList<>();
        october.path("items")
                .get(0)
                .path("releases")
                .forEach(
                        release ->
                                presented.add(
                                        release.path("platform").path("platformId").stringValue()));
        String other =
                List.of(
                                "10000000-0000-4000-8000-000000000001",
                                "10000000-0000-4000-8000-000000000002",
                                "10000000-0000-4000-8000-000000000003",
                                "10000000-0000-4000-8000-000000000004")
                        .stream()
                        .filter(platform -> !presented.contains(platform))
                        .findFirst()
                        .orElseThrow();

        HttpResponse<String> matching =
                get("/api/v1/featured-releases?month=2026-10&platformIds=" + presented.getFirst());
        OPENAPI.assertJsonResponse(matching, 200, "FeaturedReleases");
        JsonNode restricted = json(matching);
        assertThat(ids(restricted.path("items"), "canonicalTitle"))
                .containsExactly("Crimson Desert");
        restricted
                .path("items")
                .get(0)
                .path("releases")
                .forEach(
                        release ->
                                assertThat(
                                                release.path("platform")
                                                        .path("platformId")
                                                        .stringValue())
                                        .isEqualTo(presented.getFirst()));
        assertThat(matching.headers().firstValue("ETag"))
                .isNotEqualTo(
                        get("/api/v1/featured-releases?month=2026-10")
                                .headers()
                                .firstValue("ETag"));

        JsonNode none = json(get("/api/v1/featured-releases?month=2026-10&platformIds=" + other));
        assertThat(none.path("selection").path("status").stringValue())
                .isEqualTo("no_qualifying_releases");
        assertThat(none.path("items")).isEmpty();

        HttpResponse<String> unknown =
                get(
                        "/api/v1/featured-releases?month=2026-10&platformIds="
                                + presented.getFirst()
                                + "&platformIds=platform_unknown");
        assertProblem(unknown, 422, "PLATFORM_NOT_SUPPORTED");
        assertThat(
                        OBJECT_MAPPER
                                .readTree(unknown.body())
                                .path("violations")
                                .get(0)
                                .path("pointer")
                                .stringValue())
                .isEqualTo("/query/platformIds");
    }

    @Test
    void servesOnlyDiscoveryMetadataWithLocalizedSummaryFallbackAndChangingValidators()
            throws Exception {
        var game = java.util.UUID.fromString("30000000-0000-4000-8000-00000000000a");
        var original =
                jdbc.queryForMap(
                        "SELECT summary_kind,summary_text,summary_language,summary_source_kind,summary_source_name,summary_source_entity_type FROM catalogue.game_snapshot WHERE game_id=?",
                        game);
        var genreIds =
                List.of(
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID());
        try {
            for (int i = 0; i < genreIds.size(); i++) {
                jdbc.update(
                        "INSERT INTO catalogue.genre(genre_id,code,display_name) VALUES(?,?,?)",
                        genreIds.get(i),
                        "featured-test-" + i,
                        List.of("Rol (RPG)", "Aventura", "Estrategia").get(i));
                jdbc.update(
                        "INSERT INTO catalogue.game_genre(game_id,genre_id) VALUES(?,?)",
                        game,
                        genreIds.get(i));
            }
            jdbc.update(
                    "UPDATE catalogue.game_snapshot SET summary_kind='sourced',summary_text='Featured original source.',summary_language='en',summary_source_kind='external_provider',summary_source_name='IGDB',summary_source_entity_type='game_summary' WHERE game_id=?",
                    game);
            var fallback = get("/api/v1/featured-releases?month=2026-10");
            OPENAPI.assertJsonResponse(fallback, 200, "FeaturedReleases");
            var first = json(fallback).path("items").get(0);
            assertThat(ids(first.path("genres"), "name")).containsExactly("Aventura", "Estrategia");
            assertThat(first.path("summary").path("text").asString())
                    .isEqualTo("Featured original source.");
            assertThat(first.path("summary").path("language").asString()).isEqualTo("en");
            assertThat(first.path("summary").has("translation")).isFalse();
            assertThat(
                            first.has("developers")
                                    || first.has("publishers")
                                    || first.has("gameModes")
                                    || first.has("shortSummary"))
                    .isFalse();
            jdbc.update(
                    "INSERT INTO catalogue.content_translation(fingerprint,source_text,translated_text,runtime_revision,translated_at) VALUES(catalogue.spanish_source_fingerprint('Featured original source.'),'Featured original source.','Resumen destacado en español.','fixture-v1',clock_timestamp()) ON CONFLICT DO NOTHING");
            jdbc.update(
                    "INSERT INTO catalogue.game_summary_translation(game_id,fingerprint) VALUES(?,catalogue.spanish_source_fingerprint('Featured original source.'))",
                    game);
            var translated =
                    get(
                            "/api/v1/featured-releases?month=2026-10",
                            "If-None-Match",
                            fallback.headers().firstValue("ETag").orElseThrow());
            OPENAPI.assertJsonResponse(translated, 200, "FeaturedReleases");
            var summary = json(translated).path("items").get(0).path("summary");
            assertThat(summary.path("text").asString()).isEqualTo("Resumen destacado en español.");
            assertThat(summary.path("language").asString()).isEqualTo("es");
            assertThat(summary.path("provenance").path("sourceName").asString()).isEqualTo("IGDB");
            assertThat(summary.path("translation").path("current").asBoolean()).isTrue();
            jdbc.update(
                    "UPDATE catalogue.game_snapshot SET summary_text='Changed featured source.' WHERE game_id=?",
                    game);
            var stale = get("/api/v1/featured-releases?month=2026-10");
            OPENAPI.assertJsonResponse(stale, 200, "FeaturedReleases");
            var oldSummary = json(stale).path("items").get(0).path("summary");
            assertThat(oldSummary.path("text").asString())
                    .isEqualTo("Resumen destacado en español.");
            assertThat(oldSummary.path("translation").path("current").asBoolean()).isFalse();
            assertThat(stale.headers().firstValue("ETag"))
                    .isNotEqualTo(translated.headers().firstValue("ETag"));
            assertThat(stale.body())
                    .doesNotContain("OPUS", "CTranslate", "runtime_revision", "fingerprint");
        } finally {
            jdbc.update("DELETE FROM catalogue.game_summary_translation WHERE game_id=?", game);
            jdbc.update(
                    "DELETE FROM catalogue.content_translation WHERE fingerprint=catalogue.spanish_source_fingerprint('Featured original source.')");
            for (var genre : genreIds) {
                jdbc.update(
                        "DELETE FROM catalogue.game_genre WHERE game_id=? AND genre_id=?",
                        game,
                        genre);
                jdbc.update("DELETE FROM catalogue.genre WHERE genre_id=?", genre);
            }
            jdbc.update(
                    "UPDATE catalogue.game_snapshot SET summary_kind=?,summary_text=?,summary_language=?,summary_source_kind=?,summary_source_name=?,summary_source_entity_type=? WHERE game_id=?",
                    original.get("summary_kind"),
                    original.get("summary_text"),
                    original.get("summary_language"),
                    original.get("summary_source_kind"),
                    original.get("summary_source_name"),
                    original.get("summary_source_entity_type"),
                    game);
        }
    }

    @Test
    void presentsStoredProviderMediaAsApprovedCdnRenditionsWithAttribution() throws Exception {
        String crimsonDesert = "30000000-0000-4000-8000-00000000000a";
        String insert =
                "INSERT INTO catalogue.game_featured_media (game_id, media_role, media_kind,"
                        + " image_reference, width, height, transparent, source_name, source_url,"
                        + " observed_at) VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?, ?, 'IGDB',"
                        + " 'https://www.igdb.com/games/crimson-desert', now())";
        try {
            jdbc.update(insert, crimsonDesert, "image", "artwork", "arcrimson", 2560, 1440, false);
            jdbc.update(insert, crimsonDesert, "logo", "logo", "locrimson", 900, 320, true);

            HttpResponse<String> october = get("/api/v1/featured-releases?month=2026-10");
            OPENAPI.assertJsonResponse(october, 200, "FeaturedReleases");
            JsonNode item = OBJECT_MAPPER.readTree(october.body()).path("items").get(0);

            JsonNode image = item.path("featuredImage");
            assertThat(image.path("kind").stringValue()).isEqualTo("artwork");
            assertThat(image.path("presentation").stringValue()).isEqualTo("fill");
            assertThat(image.path("url").stringValue())
                    .isEqualTo("https://images.igdb.com/igdb/image/upload/t_1080p/arcrimson.webp");
            assertThat(image.path("compactUrl").stringValue())
                    .isEqualTo("https://images.igdb.com/igdb/image/upload/t_720p/arcrimson.webp");
            assertThat(image.path("alternativeText").stringValue())
                    .isEqualTo("Arte de Crimson Desert");
            assertThat(image.path("attribution").path("sourceUrl").stringValue())
                    .isEqualTo("https://www.igdb.com/games/crimson-desert");
            JsonNode logo = item.path("logo");
            assertThat(logo.path("url").stringValue())
                    .isEqualTo("https://images.igdb.com/igdb/image/upload/t_720p/locrimson.png");
            assertThat(logo.path("alternativeText").stringValue()).isEqualTo("Crimson Desert");
            assertThat(
                            meterRegistry
                                    .find("catalogue.featured.selection")
                                    .tags("status", "ranked", "lead_image", "artwork")
                                    .counter())
                    .isNotNull();
        } finally {
            jdbc.update(
                    "DELETE FROM catalogue.game_featured_media WHERE game_id = CAST(? AS uuid)",
                    crimsonDesert);
        }
    }

    @Test
    void rejectsMalformedMonthsAndUnknownParametersWithStableProblems() throws Exception {
        // A well-formed month outside the navigation bounds fails the same filter validation.
        for (String month :
                List.of(
                        "2026-13",
                        "0000-01",
                        "2026-1",
                        "26-10",
                        "",
                        "2026-10-01",
                        "2025-09",
                        "2027-02",
                        "2019-03")) {
            HttpResponse<String> response = get("/api/v1/featured-releases?month=" + month);
            assertProblem(response, 422, "FILTER_INVALID");
            assertThat(
                            OBJECT_MAPPER
                                    .readTree(response.body())
                                    .path("violations")
                                    .get(0)
                                    .path("pointer")
                                    .stringValue())
                    .isEqualTo("/query/month");
        }
        assertProblem(
                get("/api/v1/featured-releases?month=2026-10&month=2026-11"),
                422,
                "FILTER_INVALID");
        assertProblem(
                get("/api/v1/featured-releases?view=featured"), 422, "REQUEST_PARAMETER_UNKNOWN");
    }

    @Test
    void rejectsMalformedWeeksAndConflictingGranularitiesWithStableProblems() throws Exception {
        // 2025 has 52 ISO weeks, so its week 53 does not exist.
        for (String week :
                List.of("2026-W54", "2026-W00", "2026-W5", "2026W33", "2026-33", "", "2025-W53")) {
            assertPointer(get("/api/v1/featured-releases?week=" + week), "/query/week");
        }
        assertPointer(get("/api/v1/featured-releases?week=2026-W33&month=2026-08"), "/query/week");
        assertPointer(
                get("/api/v1/featured-releases?granularity=month&week=2026-W33"), "/query/week");
        assertPointer(
                get("/api/v1/featured-releases?granularity=week&month=2026-08"), "/query/month");
        assertPointer(get("/api/v1/featured-releases?granularity=day"), "/query/granularity");
        assertProblem(
                get("/api/v1/featured-releases?week=2026-W33&week=2026-W34"),
                422,
                "FILTER_INVALID");
    }

    @Test
    void measuresOnlyBoundedSelectionDimensions() throws Exception {
        get("/api/v1/featured-releases?month=2026-10");
        get("/api/v1/featured-releases?granularity=week");

        Counter ranked =
                meterRegistry
                        .find("catalogue.featured.selection")
                        .tags(
                                "status",
                                "ranked",
                                "freshness",
                                "fresh",
                                "granularity",
                                "month",
                                "period",
                                "requested")
                        .counter();
        assertThat(ranked).isNotNull();
        assertThat(ranked.count()).isPositive();
        Counter currentWeek =
                meterRegistry
                        .find("catalogue.featured.selection")
                        .tags("granularity", "week", "period", "current")
                        .counter();
        assertThat(currentWeek).isNotNull();
        assertThat(currentWeek.count()).isPositive();
        meterRegistry
                .find("catalogue.featured.selection")
                .meters()
                .forEach(
                        meter ->
                                assertThat(meter.getId().getTags())
                                        .extracting(tag -> tag.getKey())
                                        .containsExactlyInAnyOrder(
                                                "status",
                                                "freshness",
                                                "granularity",
                                                "period",
                                                "lead_image"));
    }

    private void assertPointer(HttpResponse<String> response, String pointer) {
        assertProblem(response, 422, "FILTER_INVALID");
        assertThat(
                        OBJECT_MAPPER
                                .readTree(response.body())
                                .path("violations")
                                .get(0)
                                .path("pointer")
                                .stringValue())
                .isEqualTo(pointer);
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        OPENAPI.assertJsonResponse(response, status, "Problem");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(body.path("code").stringValue()).isEqualTo(code);
        assertThat(body.path("violations")).hasSize(1);
    }

    private JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static List<String> ids(JsonNode array, String key) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.path(key).stringValue()));
        return result;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(path, null, null);
    }

    private HttpResponse<String> get(String path, String header, String value)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:%d%s".formatted(port, path)))
                        .header("Accept", "application/json")
                        .GET();
        if (header != null) {
            request.header(header, value);
        }
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedApplicationClock() {
            return Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        }
    }
}
