package com.videogameplatform.catalogue.adapter.provider.igdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderCompany;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderGameDetails;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderImage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderSummary;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderTerm;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fixture evidence for the IGDB anti-corruption layer.
 *
 * <p>The provider proof recorded three normalization traps this layer must not fall into: a
 * one-day timezone difference, generic PC absorbing DOS, and a live run that never exercised a
 * cancelled or delayed release. All three are fixtures here, together with the work-kind
 * translation the import policy depends on and the mapping failures that must isolate a record
 * instead of publishing a wrong one.
 */
class IgdbCatalogueProviderAdapterTest {

    private IgdbFixtureServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void pagesReleaseDatesInAFixedWindowAndDeduplicatesGameReferences() {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/release_dates",
                                        IgdbFixtureServer.sequence(
                                                new IgdbFixtureServer.Response(
                                                        200,
                                                        "[{\"id\":10,\"game\":100},{\"id\":11,\"game\":100}]"),
                                                new IgdbFixtureServer.Response(
                                                        200, "[{\"id\":12,\"game\":101}]"))));
        var from = LocalDate.of(2026, 3, 1);
        var to = LocalDate.of(2027, 3, 1);
        var adapter = adapter();
        var first = adapter.releaseGames(from, to, 0, 2);
        assertThat(first.gameIds()).containsExactly("100");
        assertThat(first.inspected()).isEqualTo(2);
        assertThat(first.completed()).isFalse();
        var second = adapter.releaseGames(from, to, first.nextGameId(), 2);
        assertThat(second.gameIds()).containsExactly("101");
        assertThat(second.completed()).isTrue();
        assertThat(server.requests())
                .filteredOn(r -> r.path().equals("/v4/release_dates"))
                .extracting(IgdbFixtureServer.RecordedRequest::body)
                .containsExactly(
                        "fields id,game; where date >= 1772323200 & date < 1803945600 & game > 0; sort game asc; limit 2;",
                        "fields id,game; where date >= 1772323200 & date < 1803945600 & game > 100; sort game asc; limit 2;");
    }

    @Test
    void preservesStableReleaseReferencesWhileMappingMultipleReleasesOfOneGame() {
        var work = fetchWorkFixture("game-response.json");
        assertThat(work.providerId()).isEqualTo("116530");
        assertThat(work.type()).isEqualTo(ProviderWorkType.MAIN_GAME);
        assertThat(work.releases())
                .extracting(ProviderRelease::providerId)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "11");
    }

    @Test
    void keepsCancelledAndDelayedReleasesAsProviderSignalsRatherThanDates() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.releases())
                .contains(
                        new ProviderRelease(
                                "3",
                                new ProviderPlatform("169", "Xbox Series X|S", "series-x-s"),
                                Optional.of(new ProviderRegion("8", "worldwide")),
                                new ReleaseDate.Day(LocalDate.of(2026, 9, 30)),
                                ProviderReleaseSignal.CANCELLED,
                                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN),
                        new ProviderRelease(
                                "4",
                                new ProviderPlatform("508", "Nintendo Switch 2", "switch-2"),
                                Optional.of(new ProviderRegion("1", "europe")),
                                new ReleaseDate.Day(LocalDate.of(2026, 12, 1)),
                                ProviderReleaseSignal.DELAYED,
                                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN));
    }

    /**
     * The provider timestamp is 2025-10-01T22:00:00Z. Read in the product presentation zone it
     * would become 2 October; read at UTC it stays 1 October. The authored calendar fields, when
     * present, decide on their own.
     */
    @Test
    void resolvesDayPrecisionWithoutShiftingAcrossATimeZoneBoundary() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.releases())
                .contains(
                        new ProviderRelease(
                                "1",
                                new ProviderPlatform("167", "PlayStation 5", "ps5"),
                                Optional.of(new ProviderRegion("1", "europe")),
                                new ReleaseDate.Day(LocalDate.of(2025, 10, 2)),
                                ProviderReleaseSignal.NONE,
                                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN),
                        new ProviderRelease(
                                "2",
                                new ProviderPlatform("6", "PC (Microsoft Windows)", "win"),
                                Optional.of(new ProviderRegion("8", "worldwide")),
                                new ReleaseDate.Day(LocalDate.of(2025, 10, 1)),
                                ProviderReleaseSignal.NONE,
                                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN));
    }

    @Test
    void keepsDistinctProviderPlatformsAsDistinctReferencesWithoutMerging() {
        // A legacy PC platform (DOS, provider 13) and modern Windows (provider 6) are distinct
        // provider entities. The adapter never merges them by name or slug: each release carries
        // its
        // own provider platform reference, and neither is dropped (#178 supersedes the old
        // allowlist).
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.releases())
                .filteredOn(release -> "6".equals(release.platform().providerId()))
                .extracting(ProviderRelease::providerId)
                .containsExactly("2", "8");
        assertThat(work.releases())
                .filteredOn(release -> "13".equals(release.platform().providerId()))
                .singleElement()
                .satisfies(
                        release ->
                                assertThat(release.platform())
                                        .isEqualTo(new ProviderPlatform("13", "DOS", "dos")));
    }

    @Test
    void resolvesAMissingReleaseRegionToTheUnknownSentinelWithoutAProviderReference() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.releases())
                .filteredOn(release -> "11".equals(release.providerId()))
                .singleElement()
                .satisfies(release -> assertThat(release.region()).isEmpty());
    }

    @Test
    void isolatesUnmappableRecordsInsteadOfPublishingThem() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        // Every platform with a provider id is acquirable now; only a missing platform reference
        // and
        // an unreadable date remain mapping failures.
        assertThat(work.releases()).hasSize(9);
        assertThat(work.mappingFailures())
                .containsExactlyInAnyOrder(
                        ProviderMappingFailure.RECORD_UNREADABLE,
                        ProviderMappingFailure.RELEASE_DATE_INVALID);
    }

    @Test
    void reportsACoverThatDoesNotSatisfyTheApprovedReferenceShape() {
        ProviderWork work = fetchWorkFixture("unapproved-cover-game-response.json");

        assertThat(work.cover()).isEmpty();
        assertThat(work.mappingFailures()).contains(ProviderMappingFailure.COVER_REFERENCE_INVALID);
    }

    @Test
    void translatesAnUnreadableProviderPayloadIntoAStableCodeWithoutTheBody() {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                IgdbFixtureServer.always(
                                        200, IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                IgdbFixtureServer.always(200, "{\"unexpected\":\"shape\"}")));

        assertThatThrownBy(() -> adapter().fetchWorks(List.of("116530")))
                .isInstanceOf(ProviderRequestException.class)
                .satisfies(
                        thrown -> {
                            ProviderRequestException failure = (ProviderRequestException) thrown;
                            assertThat(failure.code())
                                    .isEqualTo(ProviderFailureCode.PROVIDER_RESPONSE_INVALID);
                            assertThat(failure.getMessage())
                                    .isEqualTo("PROVIDER_RESPONSE_INVALID")
                                    .doesNotContain("unexpected");
                        });
    }

    @Test
    void boundsEveryRequestItSends() {
        fetchWorkFixture("game-response.json");

        assertThat(server.requests())
                .filteredOn(request -> "/v4/release_dates".equals(request.path()))
                .singleElement()
                .satisfies(
                        request ->
                                assertThat(request.body())
                                        .contains("where game = (116530)")
                                        .contains("limit 26;"));
        assertThat(server.requests())
                .allSatisfy(
                        request ->
                                assertThat(request.path())
                                        .isIn("/oauth2/token", "/v4/games", "/v4/release_dates"));
    }

    @Test
    void readsTheImageMetadataOfAWorkInProviderOrderAndIgnoresUnusableImages() {
        ProviderWork work = fetchWorkFixture("media-game-response.json");

        // Rising image identity is the provider's stable order; only metadata ever crosses. Artwork
        // labelled as a cover, a game logo or an icon is not landscape art: it is never offered,
        // and leaving it out is not a mapping failure. Any other label stays untitled art.
        assertThat(work.images())
                .containsExactly(
                        new ProviderImage(
                                ImageKind.ARTWORK, "arfirst", 3840, 2160, false, false, true),
                        new ProviderImage(ImageKind.ARTWORK, "arlater", 1920, 1080, false, false),
                        new ProviderImage(ImageKind.ARTWORK, "arbanner", 3840, 1240, false, false),
                        new ProviderImage(ImageKind.SCREENSHOT, "scmoving", 1280, 720, true, true));
        assertThat(work.attributionUrl()).contains("https://www.igdb.com/games/ghost-of-yotei");
        assertThat(work.mappingFailures())
                .containsOnlyOnce(ProviderMappingFailure.IMAGE_REFERENCE_INVALID);
        assertThat(server.requests())
                .filteredOn(request -> "/v4/games".equals(request.path()))
                .singleElement()
                .satisfies(
                        request ->
                                assertThat(request.body())
                                        .contains(
                                                "artworks.image_id,artworks.width,artworks.height,"
                                                        + "artworks.alpha_channel,artworks.animated",
                                                "screenshots.image_id,screenshots.width,"
                                                        + "screenshots.height,"
                                                        + "screenshots.alpha_channel,"
                                                        + "screenshots.animated"));
    }

    @Test
    void readsLogosByGameInPagesOfRisingLogoIdentityAndIgnoresAnUnusableLogo() {
        StringBuilder fullPage = new StringBuilder("[");
        for (int id = 1; id <= 500; id++) {
            fullPage.append(id == 1 ? "" : ",")
                    .append("{\"id\":")
                    .append(id)
                    .append(",\"game\":100,\"image_id\":\"lo")
                    .append(id)
                    .append("\",\"width\":900,\"height\":320,\"alpha_channel\":true}");
        }
        fullPage.append("]");
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                IgdbFixtureServer.always(
                                        200, IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/logos",
                                IgdbFixtureServer.sequence(
                                        new IgdbFixtureServer.Response(200, fullPage.toString()),
                                        new IgdbFixtureServer.Response(
                                                200,
                                                "[{\"id\":501,\"game\":101,\"image_id\":"
                                                        + "\"lolast\",\"width\":600,"
                                                        + "\"height\":200},"
                                                        + "{\"id\":502,\"game\":101,"
                                                        + "\"image_id\":\"lonosize\"}]"))));
        var registry = new SimpleMeterRegistry();

        var batch = adapter(registry).logos(List.of("100", "101", "102"));

        assertThat(batch.logos()).containsOnlyKeys("100", "101");
        assertThat(batch.logos().get("100")).hasSize(500);
        assertThat(batch.logos().get("100").getFirst())
                .isEqualTo(new ProviderImage(ImageKind.LOGO, "lo1", 900, 320, true, false));
        assertThat(batch.logos().get("101"))
                .containsExactly(
                        new ProviderImage(ImageKind.LOGO, "lolast", 600, 200, false, false));
        assertThat(batch.statistics().requests()).isEqualTo(3);
        assertThat(
                        registry.get("catalogue.synchronization.provider.mapping.failure")
                                .tag("reason", "image_reference_invalid")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(server.requests())
                .filteredOn(request -> "/v4/logos".equals(request.path()))
                .extracting(IgdbFixtureServer.RecordedRequest::body)
                .containsExactly(
                        "fields game,image_id,width,height,alpha_channel,animated;"
                                + " where game = (100,101,102) & id > 0; sort id asc; limit 500;",
                        "fields game,image_id,width,height,alpha_channel,animated;"
                                + " where game = (100,101,102) & id > 500; sort id asc; limit 500;");
    }

    @Test
    void stopsReadingLogosPastTheirBoundedNumberOfPages() {
        IgdbFixtureServer.Response[] pages = new IgdbFixtureServer.Response[5];
        for (int page = 0; page < pages.length; page++) {
            StringBuilder body = new StringBuilder("[");
            for (int row = 1; row <= 500; row++) {
                int id = page * 500 + row;
                body.append(row == 1 ? "" : ",")
                        .append("{\"id\":")
                        .append(id)
                        .append(",\"game\":100,\"image_id\":\"lo")
                        .append(id)
                        .append("\",\"width\":900,\"height\":320,\"alpha_channel\":true}");
            }
            pages[page] = new IgdbFixtureServer.Response(200, body.append("]").toString());
        }
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                IgdbFixtureServer.always(
                                        200, IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/logos",
                                IgdbFixtureServer.sequence(pages)));

        assertThatThrownBy(() -> adapter().logos(List.of("100")))
                .isInstanceOf(ProviderRequestException.class)
                .hasMessage("PROVIDER_RESPONSE_INVALID");
        assertThat(server.requests())
                .filteredOn(request -> "/v4/logos".equals(request.path()))
                .hasSize(4);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "null",
                "[null]",
                "[{\"game\":100,\"image_id\":\"lo\",\"width\":900,\"height\":320}]",
                "[{\"id\":7,\"game\":999,\"image_id\":\"lo\",\"width\":900," + "\"height\":320}]",
                "[{\"id\":7,\"image_id\":\"lo\",\"width\":900,\"height\":320}]",
                "[{\"id\":7,\"game\":100,\"image_id\":\"lo\",\"width\":900,\"height\":320},"
                        + "{\"id\":7,\"game\":100,\"image_id\":\"lo2\",\"width\":900,"
                        + "\"height\":320}]"
            })
    void rejectsAnInconsistentLogoAnswerAsAWholeSoNoLogoIsGuessed(String payload) {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                IgdbFixtureServer.always(
                                        200, IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/logos",
                                IgdbFixtureServer.always(200, payload)));

        assertThatThrownBy(() -> adapter().logos(List.of("100", "101")))
                .isInstanceOf(ProviderRequestException.class)
                .hasMessage("PROVIDER_RESPONSE_INVALID");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", ",\"hypes\":0", ",\"hypes\":292"})
    void acquiresHypesAndEligibilityWithTheWorkWithoutAPopularityRequest(String hypes) {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                        IgdbFixtureServer.always(
                                                200,
                                                "[{\"id\":100,\"name\":\"New Game\",\"first_release_date\":1790812800"
                                                        + hypes
                                                        + "}]"),
                                "/v4/release_dates", IgdbFixtureServer.always(200, "[]")));
        var work = adapter().fetchWorks(List.of("100")).works().getFirst();
        assertThat(work.featuredEvidence())
                .hasValueSatisfying(
                        e -> {
                            assertThat(e.firstReleaseDate()).contains(LocalDate.of(2026, 10, 1));
                            assertThat(e.edition()).isFalse();
                            if (hypes.endsWith("292")) assertThat(e.hypes()).contains(292L);
                            else assertThat(e.hypes()).isEmpty();
                        });
        assertThat(server.requests()).noneMatch(r -> r.path().contains("popularity"));
        assertThat(server.requests())
                .filteredOn(r -> r.path().equals("/v4/games"))
                .allSatisfy(
                        r ->
                                assertThat(r.body())
                                        .contains("hypes,first_release_date,version_parent"));
    }

    @Test
    void normalizesEditionToABooleanWithoutLeakingItsParentIdentity() {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                        IgdbFixtureServer.always(
                                                200,
                                                "[{\"id\":100,\"name\":\"Premium Edition\",\"hypes\":10,\"version_parent\":99}]"),
                                "/v4/release_dates", IgdbFixtureServer.always(200, "[]")));
        assertThat(adapter().fetchWorks(List.of("100")).works().getFirst().featuredEvidence())
                .hasValueSatisfying(
                        e -> {
                            assertThat(e.edition()).isTrue();
                            assertThat(e.firstReleaseDate()).isEmpty();
                        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"-1", "1.5", "9223372036854775808"})
    void invalidFeaturedEvidenceKeepsTheWorkUsableAndReportsABoundedMappingFailure(
            String invalidHypes) {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                        IgdbFixtureServer.always(
                                                200,
                                                "[{\"id\":100,\"name\":\"Game\",\"hypes\":"
                                                        + invalidHypes
                                                        + "}]"),
                                "/v4/release_dates", IgdbFixtureServer.always(200, "[]")));
        var work = adapter().fetchWorks(List.of("100")).works().getFirst();
        assertThat(work.featuredEvidence()).isEmpty();
        assertThat(work.mappingFailures())
                .contains(ProviderMappingFailure.FEATURED_EVIDENCE_INVALID);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "null",
                "[null]",
                "[{\"id\":10,\"game\":100},{\"id\":10,\"game\":100}]",
                "[{\"id\":10,\"game\":101},{\"id\":9,\"game\":100}]",
                "[{\"id\":10,\"game\":100},{\"id\":11,\"game\":101},{\"id\":12,\"game\":102}]"
            })
    void rejectsUnsafeWindowPagesWithoutReturningProgress(String payload) {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/release_dates", IgdbFixtureServer.always(200, payload)));
        assertThatThrownBy(
                        () ->
                                adapter()
                                        .releaseGames(
                                                LocalDate.parse("2026-03-01"),
                                                LocalDate.parse("2027-03-01"),
                                                0,
                                                2))
                .isInstanceOf(ProviderRequestException.class)
                .hasMessage("PROVIDER_RESPONSE_INVALID");
    }

    @Test
    void rejectsReleaseOverflowRatherThanPublishingATruncatedGame() {
        String payload =
                java.util.stream.LongStream.rangeClosed(1, 26)
                        .mapToObj(id -> "{\"id\":" + id + ",\"game\":116530}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("game-response.json")),
                                "/v4/release_dates", IgdbFixtureServer.always(200, payload)));
        assertThatThrownBy(() -> adapter().fetchWorks(List.of("116530")))
                .isInstanceOf(ProviderRequestException.class)
                .hasMessage("PROVIDER_RESPONSE_INVALID");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "Full Release,FULL_RELEASE",
        "Early Access,EARLY_ACCESS",
        "Advanced Access,ADVANCE_ACCESS",
        "Beta,BETA",
        "Alpha,ALPHA",
        ",UNKNOWN",
        "Future Stage,UNKNOWN",
        "Offline,UNKNOWN",
        "Cancelled,UNKNOWN",
        "Digital Compatibility Release,UNKNOWN",
        "Next-Gen Optimization Patch Release,UNKNOWN"
    })
    void normalizesStagesIndependentlyOfLifecycleAndNeverRejectsUnsupportedEvidence(
            String name, String expected) {
        String status = name == null ? "" : ",\"status\":{\"name\":\"" + name + "\"}";
        String payload =
                "[{\"id\":920916,\"game\":116530,\"y\":2026,\"m\":10,\"d\":2,\"platform\":{\"id\":167,\"name\":\"PlayStation 5\"}"
                        + status
                        + "}]";
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("game-response.json")),
                                "/v4/release_dates", IgdbFixtureServer.always(200, payload)));
        var work = adapter().fetchWorks(List.of("116530")).works().getFirst();
        assertThat(work.releases())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.stage().name()).isEqualTo(expected);
                            assertThat(r.signal())
                                    .isEqualTo(
                                            "Cancelled".equals(name)
                                                    ? ProviderReleaseSignal.CANCELLED
                                                    : ProviderReleaseSignal.NONE);
                        });
    }

    @Test
    void requestsOnlyTheApprovedDetailFieldsWithTheGame() {
        fetchWorkFixture("details-game-response.json");

        assertThat(server.requests())
                .filteredOn(r -> r.path().equals("/v4/games"))
                .singleElement()
                .extracting(IgdbFixtureServer.RecordedRequest::body)
                .asString()
                .contains(
                        ",summary,",
                        "involved_companies.developer,involved_companies.publisher,"
                                + "involved_companies.company.id,involved_companies.company.name",
                        "genres.id,genres.name,genres.slug",
                        "game_modes.id,game_modes.name,game_modes.slug")
                .doesNotContain("themes", "franchise", "age_rating", "websites", "videos");
    }

    /**
     * IGDB credits companies through company-credit records with role flags. Only developer and
     * publisher become product vocabulary: porting-only and supporting-only credits are dropped, a
     * company holding both roles is listed in both, and a repeated credit is one company.
     */
    @Test
    void separatesDevelopersFromPublishersAndNormalizesMultipleGenresAndModes() {
        ProviderWork work = fetchWorkFixture("details-game-response.json");

        assertThat(work.mappingFailures()).doesNotContain(ProviderMappingFailure.DETAILS_INVALID);
        assertThat(work.details())
                .hasValueSatisfying(
                        details -> {
                            assertThat(details.summary())
                                    .contains(
                                            new ProviderSummary(
                                                    "A lone warrior seeks revenge in the lands"
                                                            + " around Mount Yotei.\nSecond line.",
                                                    "en"));
                            assertThat(details.developers())
                                    .containsExactly(
                                            new ProviderCompany("3045", "Sucker Punch Productions"),
                                            new ProviderCompany("5050", "Self Made Games"));
                            assertThat(details.publishers())
                                    .containsExactly(
                                            new ProviderCompany(
                                                    "10100", "Sony Interactive Entertainment"),
                                            new ProviderCompany("5050", "Self Made Games"));
                            assertThat(details.genres())
                                    .containsExactly(
                                            new ProviderTerm("31", "Adventure", "adventure"),
                                            new ProviderTerm(
                                                    "12", "Role-playing (RPG)", "role-playing-rpg"),
                                            new ProviderTerm(
                                                    "25",
                                                    "Hack and slash/Beat 'em up",
                                                    "hack-and-slash-beat-em-up"));
                            assertThat(details.gameModes())
                                    .extracting(ProviderTerm::providerId)
                                    .containsExactly("1", "2", "3");
                        });
    }

    @Test
    void treatsMissingDetailMetadataAsValidEmptyEvidence() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.mappingFailures()).doesNotContain(ProviderMappingFailure.DETAILS_INVALID);
        assertThat(work.details())
                .hasValueSatisfying(
                        details -> {
                            assertThat(details.summary()).isEmpty();
                            assertThat(details.developers()).isEmpty();
                            assertThat(details.publishers()).isEmpty();
                            assertThat(details.genres()).isEmpty();
                            assertThat(details.gameModes()).isEmpty();
                        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "\"summary\":\"   \",\"genres\":[]",
                "\"summary\":null,\"involved_companies\":[{\"id\":1,\"company\":{\"id\":2,\"name\":\"\"},\"porting\":true}]"
            })
    void blankSummaryAndUncreditedRolesAreAbsenceNotFailure(String fields) {
        var work = fetchInlineWork(fields);
        assertThat(work.details())
                .hasValueSatisfying(
                        details -> {
                            assertThat(details.summary()).isEmpty();
                            assertThat(details.developers()).isEmpty();
                        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "\"involved_companies\":[{\"id\":1,\"developer\":true}]",
                "\"involved_companies\":[{\"id\":1,\"company\":{\"id\":2,\"name\":\" \"},\"publisher\":true}]",
                "\"involved_companies\":[null]",
                "\"genres\":[{\"name\":\"Adventure\"}]",
                "\"game_modes\":[{\"id\":1,\"name\":\"\"}]",
                "\"genres\":[null]",
                "\"summary\":\"bad\\u0000text\""
            })
    void incoherentDetailMetadataYieldsNoDetailsButKeepsTheWorkUsable(String fields) {
        var work = fetchInlineWork(fields);

        assertThat(work.details()).isEmpty();
        assertThat(work.mappingFailures()).contains(ProviderMappingFailure.DETAILS_INVALID);
        assertThat(work.title()).isEqualTo("Game");
    }

    @Test
    void rejectsImplausiblyLargeDetailListsAsAWhole() {
        String genres =
                java.util.stream.IntStream.rangeClosed(1, ProviderGameDetails.MAX_ENTRIES + 1)
                        .mapToObj(id -> "{\"id\":" + id + ",\"name\":\"Genre " + id + "\"}")
                        .collect(java.util.stream.Collectors.joining(",", "\"genres\":[", "]"));
        String summary = "\"summary\":\"" + "x".repeat(ProviderSummary.MAX_LENGTH + 1) + "\"";

        assertThat(fetchInlineWork(genres).details()).isEmpty();
        assertThat(fetchInlineWork(summary).details()).isEmpty();
    }

    private ProviderWork fetchInlineWork(String fields) {
        if (server != null) {
            server.close();
        }
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                        IgdbFixtureServer.always(
                                                200,
                                                "[{\"id\":100,\"name\":\"Game\"," + fields + "}]"),
                                "/v4/release_dates", IgdbFixtureServer.always(200, "[]")));
        return adapter().fetchWorks(List.of("100")).works().getFirst();
    }

    private ProviderWork fetchWorkFixture(String gameFixture) {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                "/oauth2/token",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture("token-response.json")),
                                "/v4/games",
                                        IgdbFixtureServer.always(
                                                200, IgdbFixtureServer.fixture(gameFixture)),
                                "/v4/release_dates",
                                        IgdbFixtureServer.always(
                                                200,
                                                IgdbFixtureServer.fixture(
                                                        "release-dates-response.json"))));
        return adapter().fetchWorks(List.of("116530")).works().get(0);
    }

    private IgdbCatalogueProviderAdapter adapter() {
        return adapter(new SimpleMeterRegistry());
    }

    private IgdbCatalogueProviderAdapter adapter(SimpleMeterRegistry registry) {
        IgdbApiSettings settings = settings();
        ObjectMapper mapper =
                JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build();
        return new IgdbCatalogueProviderAdapter(
                new IgdbApiClient(HttpClient.newHttpClient(), mapper, settings),
                mapper,
                settings,
                new CatalogueSynchronizationMetrics(registry));
    }

    private IgdbApiSettings settings() {
        return new IgdbApiSettings(
                "fixture-client",
                "fixture-secret",
                server.uri("/oauth2/token"),
                server.uri("/v4/"),
                Duration.ofSeconds(5),
                IgdbApiSettings.MAX_REQUESTS_PER_SECOND,
                1,
                Duration.ofMillis(1),
                25);
    }
}
