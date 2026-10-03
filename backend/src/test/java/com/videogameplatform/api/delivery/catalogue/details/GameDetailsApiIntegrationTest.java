package com.videogameplatform.api.delivery.catalogue.details;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.internal.ReleaseReconciliationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.ReleaseWrite;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
    @Autowired CatalogueSynchronizationStore synchronizationStore;
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
    void completeReconciliationRemovesStalePublicEvidenceAndEligibilityAndRotatesValidator()
            throws Exception {
        admin.update(
                "UPDATE catalogue.release_snapshot SET source_kind='external_provider',source_name='IGDB',source_entity_type='release_date',date_precision='year',exact_date=NULL,release_year=2026,release_status='announced' WHERE game_id=?",
                game);
        admin.update(
                "INSERT INTO catalogue.game_external_reference(game_id,provider,provider_entity_type,provider_id) VALUES (?,'IGDB','game','900001')",
                game);
        admin.update(
                "INSERT INTO catalogue.release_external_reference(provider,provider_id,release_id,game_id) SELECT 'IGDB','1001',release_id,game_id FROM catalogue.release_snapshot WHERE game_id=?",
                game);
        var previous = synchronizationStore.loadGame("900001", 10).orElseThrow();
        var before = get(game.toString());
        var evidence =
                new ProviderRelease(
                        "2001",
                        new ProviderPlatform("167", "PlayStation 5", "ps5"),
                        Optional.of(new ProviderRegion("8", "Worldwide")),
                        new ReleaseDate.Day(LocalDate.parse("2026-08-13")),
                        ProviderReleaseSignal.NONE,
                        com.videogameplatform.catalogue.domain.ReleaseStage.FULL_RELEASE);
        var at = Instant.parse("2026-08-13T08:00:00Z");
        var run =
                synchronizationStore
                        .beginRun(
                                "IGDB",
                                new CatalogueSynchronizationRequest(
                                        LocalDate.parse("2026-08-13"),
                                        LocalDate.parse("2026-08-13")),
                                at,
                                Duration.ofMinutes(30))
                        .orElseThrow();
        try {
            var result =
                    synchronizationStore.saveGame(
                            run,
                            new GameWrite(
                                    "900001",
                                    game,
                                    false,
                                    previous.title(),
                                    previous.slug(),
                                    previous.cover(),
                                    List.of(
                                            new ReleaseWrite(
                                                    "2001",
                                                    ReleaseReconciliationPolicy.reconcile(
                                                                    evidence, null, at, at, "IGDB")
                                                            .orElseThrow())),
                                    at,
                                    java.util.Set.of("2001"),
                                    10));
            assertThat(result.deletedReleases()).isEqualTo(1);
            var after = get(game.toString());
            CONTRACT.assertJsonResponse(after, 200, "GameDetails");
            var body = JSON.readTree(after.body());
            assertThat(body.path("releases").size()).isEqualTo(1);
            assertThat(body.path("releases").get(0).path("stage").asString())
                    .isEqualTo("full_release");
            assertThat(
                            body.path("releases")
                                    .get(0)
                                    .path("releaseDate")
                                    .path("precision")
                                    .asString())
                    .isEqualTo("day");
            assertThat(after.headers().firstValue("ETag"))
                    .isNotEqualTo(before.headers().firstValue("ETag"));
            assertThat(body.path("ratingEligibility").path("eligible").asBoolean()).isTrue();
            // A valid empty complete set removes the only eligible current Release.
            synchronizationStore.saveGame(
                    run,
                    new GameWrite(
                            "900001",
                            game,
                            false,
                            previous.title(),
                            previous.slug(),
                            previous.cover(),
                            List.of(),
                            at,
                            java.util.Set.of(),
                            10));
            var empty = JSON.readTree(get(game.toString()).body());
            assertThat(empty.path("releases").size()).isZero();
            assertThat(empty.path("ratingEligibility").path("eligible").asBoolean()).isFalse();
        } finally {
            admin.update("DELETE FROM catalogue.synchronization_run WHERE run_id=?", run);
        }
    }

    @Test
    void stageChangesPresentationAndContractWhileEligibilityStillUsesTheEarlierRelease()
            throws Exception {
        admin.update(
                "UPDATE catalogue.release_snapshot SET release_stage='early_access',exact_date=date '2026-08-12' WHERE game_id=?",
                game);
        release("day", "announced", "provider_only", "not_required");
        admin.update(
                "UPDATE catalogue.release_snapshot SET release_stage='full_release',exact_date=date '2026-10-02' WHERE game_id=? AND release_status='announced'",
                game);
        var response = get(game.toString());
        CONTRACT.assertJsonResponse(response, 200, "GameDetails");
        var body = JSON.readTree(response.body());
        assertThat(body.path("releases").get(0).path("stage").asString()).isEqualTo("full_release");
        assertThat(body.path("releases").get(0).path("status").asString()).isEqualTo("scheduled");
        assertThat(body.path("releases").get(1).path("stage").asString()).isEqualTo("early_access");
        assertThat(body.path("ratingEligibility").path("eligible").asBoolean()).isTrue();
        assertThat(body.path("ratingEligibility").path("reason").asString())
                .isEqualTo("ELIGIBLE_RELEASE_FOUND");
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
        // The same product label the release filters serve, never a provider or seed name.
        assertThat(release.path("region").path("name").asString()).isEqualTo("Europa");
        assertThat(release.path("stage").asString()).isEqualTo("unknown");
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
        "day,announced,provider_only,not_required,ELIGIBLE_RELEASE_FOUND",
        "day,announced,provider_only,required,RELEASE_REVIEW_REQUIRED",
        "month,released,provider_only,not_required,RELEASE_NOT_OCCURRED",
        "quarter,released,verified,not_required,RELEASE_NOT_OCCURRED",
        "year,released,verified,not_required,RELEASE_NOT_OCCURRED",
        "unknown,released,provider_only,not_required,RELEASE_DATE_UNCERTAIN",
        "unknown,released,verified,not_required,ELIGIBLE_RELEASE_FOUND",
        "day,released,verified,required,RELEASE_REVIEW_REQUIRED",
        "day,delayed,verified,not_required,RELEASE_NOT_OCCURRED",
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
    void listsThePresentedRecordFirstWhileEligibilityStillUsesEveryRecord() throws Exception {
        // A verified upcoming day outranks the provider-only day that already occurred on the same
        // platform and region, yet that additional record still proves eligibility.
        UUID verifiedUpcoming = UUID.randomUUID();
        admin.update(
                "INSERT INTO catalogue.game_release VALUES (?, ?, now())", verifiedUpcoming, game);
        admin.update(
                """
            INSERT INTO catalogue.release_snapshot(publication_id, release_id, game_id, platform_id, region_id,
                date_precision, exact_date, release_status, source_kind, source_name, source_entity_type,
                last_synchronized_at, last_verified_at, verification_level, review_status)
            SELECT publication_id, ?, ?, '10000000-0000-4000-8000-000000000001',
                '20000000-0000-4000-8000-000000000002', 'day', date '2026-09-01', 'announced',
                'official_source', 'Official source', 'release', timestamptz '2026-01-01 00:00:00Z',
                timestamptz '2026-01-01 00:00:00Z', 'verified', 'not_required'
            FROM catalogue.catalogue_publication WHERE is_current
            """,
                verifiedUpcoming,
                game);

        var response = get(game.toString());
        CONTRACT.assertJsonResponse(response, 200, "GameDetails");
        var body = JSON.readTree(response.body());
        assertThat(body.path("releases")).hasSize(2);
        assertThat(body.path("releases").get(0).path("releaseId").asString())
                .isEqualTo(verifiedUpcoming.toString());
        assertThat(body.path("releases").get(0).path("status").asString()).isEqualTo("scheduled");
        assertThat(body.path("releases").get(1).path("status").asString()).isEqualTo("released");
        assertThat(body.path("ratingEligibility").path("reason").asString())
                .isEqualTo("ELIGIBLE_RELEASE_FOUND");
    }

    @ParameterizedTest
    @CsvSource({"not_required,347668,752219", "required,347669,752220"})
    void resynchronizingTheRequiemReleaseRecomputesReviewAndPublicEligibility(
            String previousReview, String gameReference, String releaseReference) throws Exception {
        // Private-dev #211/#226 shape: known provider-only evidence, including stale review.
        // Normal reconciliation must correct the persisted gate rather than bypass its consumer.
        admin.update(
                "UPDATE catalogue.release_snapshot SET review_status=? WHERE game_id=?",
                previousReview,
                game);
        var date = new ReleaseDate.Day(LocalDate.parse("2026-02-27"));
        admin.update(
                "UPDATE catalogue.game_snapshot SET canonical_title = 'Resident Evil Requiem' WHERE game_id = ?",
                game);
        admin.update(
                "UPDATE catalogue.release_snapshot SET exact_date = ?, platform_id = '10000000-0000-4000-8000-000000000003', region_id = '20000000-0000-4000-8000-000000000001', source_kind = 'external_provider', source_name = 'IGDB', source_entity_type = 'release_date' WHERE game_id = ?",
                date.date(),
                game);
        admin.update(
                "INSERT INTO catalogue.game_external_reference(game_id, provider, provider_entity_type, provider_id) VALUES (?, 'IGDB', 'game', ?)",
                game,
                gameReference);
        admin.update(
                "INSERT INTO catalogue.release_external_reference(provider, provider_id, release_id, game_id) SELECT 'IGDB', ?, release_id, game_id FROM catalogue.release_snapshot WHERE game_id = ?",
                releaseReference,
                game);

        var before = get(game.toString());
        CONTRACT.assertJsonResponse(before, 200, "GameDetails");
        assertThat(
                        JSON.readTree(before.body())
                                .path("ratingEligibility")
                                .path("eligible")
                                .asBoolean())
                .isEqualTo(previousReview.equals("not_required"));
        assertThat(
                        JSON.readTree(before.body())
                                .path("releases")
                                .get(0)
                                .path("reviewStatus")
                                .asString())
                .isEqualTo(previousReview);
        var old = synchronizationStore.loadGame(gameReference, 10).orElseThrow();
        var evidence =
                new ProviderRelease(
                        releaseReference,
                        new ProviderPlatform("6", "Windows PC", "windows-pc"),
                        Optional.of(new ProviderRegion("8", "Worldwide")),
                        date,
                        ProviderReleaseSignal.NONE,
                        com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN);
        Instant synchronizedAt = Instant.parse("2026-08-13T08:00:00Z");
        var reconciled =
                ReleaseReconciliationPolicy.reconcile(
                                evidence,
                                old.releases().get(releaseReference),
                                synchronizedAt,
                                synchronizedAt,
                                "IGDB")
                        .orElseThrow();
        var run =
                synchronizationStore
                        .beginRun(
                                "IGDB",
                                new CatalogueSynchronizationRequest(date.date(), date.date()),
                                synchronizedAt,
                                Duration.ofMinutes(30))
                        .orElseThrow();
        try {
            synchronizationStore.saveGame(
                    run,
                    new GameWrite(
                            gameReference,
                            old.gameId(),
                            false,
                            old.title(),
                            old.slug(),
                            old.cover(),
                            List.of(new ReleaseWrite(releaseReference, reconciled)),
                            synchronizedAt,
                            java.util.Set.of(releaseReference),
                            10));
        } finally {
            admin.update("DELETE FROM catalogue.synchronization_run WHERE run_id = ?", run);
        }

        var response = get(game.toString());
        CONTRACT.assertJsonResponse(response, 200, "GameDetails");
        var body = JSON.readTree(response.body());
        assertThat(body.path("canonicalTitle").asString()).isEqualTo("Resident Evil Requiem");
        assertThat(body.path("ratingEligibility").path("reason").asString())
                .isEqualTo("ELIGIBLE_RELEASE_FOUND");
        assertThat(body.path("ratingEligibility").path("eligible").asBoolean()).isTrue();
        assertThat(body.path("releases").get(0).path("releaseDate").path("value").asString())
                .isEqualTo("2026-02-27");
        assertThat(body.path("releases").get(0).path("status").asString()).isEqualTo("released");
        assertThat(body.path("releases").get(0).path("reviewStatus").asString())
                .isEqualTo("not_required");
        assertThat(
                        admin.queryForObject(
                                "SELECT release_status FROM catalogue.release_snapshot WHERE game_id = ?",
                                String.class,
                                game))
                .isEqualTo("announced");
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
