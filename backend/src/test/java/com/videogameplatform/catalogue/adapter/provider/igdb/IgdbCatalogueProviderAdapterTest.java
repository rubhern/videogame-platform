package com.videogameplatform.catalogue.adapter.provider.igdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
                .containsExactly("1", "2", "3", "4", "7", "8", "11");
    }

    @Test
    void keepsCancelledAndDelayedReleasesAsProviderSignalsRatherThanDates() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.releases())
                .contains(
                        new ProviderRelease(
                                "3",
                                "xbox-series",
                                "worldwide",
                                new ReleaseDate.Day(LocalDate.of(2026, 9, 30)),
                                ProviderReleaseSignal.CANCELLED),
                        new ProviderRelease(
                                "4",
                                "nintendo-switch-2",
                                "europe",
                                new ReleaseDate.Day(LocalDate.of(2026, 12, 1)),
                                ProviderReleaseSignal.DELAYED));
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
                                "playstation-5",
                                "europe",
                                new ReleaseDate.Day(LocalDate.of(2025, 10, 2)),
                                ProviderReleaseSignal.NONE),
                        new ProviderRelease(
                                "2",
                                "windows-pc",
                                "worldwide",
                                new ReleaseDate.Day(LocalDate.of(2025, 10, 1)),
                                ProviderReleaseSignal.NONE));
    }

    @Test
    void neverFoldsALegacyPcPlatformIntoTheModernWindowsPlatform() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.releases())
                .filteredOn(release -> "windows-pc".equals(release.platformCode()))
                .hasSize(2)
                .allSatisfy(
                        release -> assertThat(release.regionCode()).isIn("worldwide", "europe"));
        assertThat(work.mappingFailures()).contains(ProviderMappingFailure.PLATFORM_NOT_SUPPORTED);
    }

    @Test
    void isolatesUnmappableRecordsInsteadOfPublishingThem() {
        ProviderWork work = fetchWorkFixture("game-response.json");

        assertThat(work.releases()).hasSize(7);
        assertThat(work.mappingFailures())
                .containsExactlyInAnyOrder(
                        ProviderMappingFailure.PLATFORM_NOT_SUPPORTED,
                        ProviderMappingFailure.REGION_NOT_SUPPORTED,
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
                new CatalogueSynchronizationMetrics(new SimpleMeterRegistry()));
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
                25,
                Map.of(
                        "ps5", "playstation-5",
                        "switch-2", "nintendo-switch-2",
                        "win", "windows-pc",
                        "series-x-s", "xbox-series"),
                Map.of("worldwide", "worldwide", "europe", "europe"),
                "unknown");
    }
}
