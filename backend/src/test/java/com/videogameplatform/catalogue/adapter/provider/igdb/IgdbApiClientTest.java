package com.videogameplatform.catalogue.adapter.provider.igdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Transport evidence: authentication, the approved rate, bounded retries and safe failures. */
class IgdbApiClientTest {

    private static final String TOKEN_FIXTURE = IgdbFixtureServer.fixture("token-response.json");
    private static final String GAMES_PATH = "/v4/games";
    private static final String TOKEN_PATH = "/oauth2/token";

    private IgdbFixtureServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void authenticatesOnceAndReusesTheApplicationTokenForLaterRequests() {
        startServer(IgdbFixtureServer.always(200, "[]"));
        IgdbApiClient client = client(3, Duration.ofMillis(1));

        client.query("games", "fields id;");
        client.query("games", "fields id;");

        List<IgdbFixtureServer.RecordedRequest> tokenRequests = requests(TOKEN_PATH);
        assertThat(tokenRequests).hasSize(1);
        assertThat(requests(GAMES_PATH))
                .hasSize(2)
                .allSatisfy(
                        request -> {
                            assertThat(request.authorization())
                                    .isEqualTo("Bearer fixture-access-token");
                            assertThat(request.clientId()).isEqualTo("fixture-client");
                        });
    }

    @Test
    void spacesRequestsToTheApprovedLocalRate() {
        startServer(IgdbFixtureServer.always(200, "[]"));
        IgdbApiClient client =
                client(IgdbApiSettings.MAX_REQUESTS_PER_SECOND, Duration.ofMillis(1));

        client.query("games", "fields id;");
        client.query("games", "fields id;");
        client.query("games", "fields id;");

        List<IgdbFixtureServer.RecordedRequest> gameRequests = requests(GAMES_PATH);
        assertThat(gameRequests).hasSize(3);
        long minimumSpacingNanos =
                (long) (1_000_000_000d / IgdbApiSettings.MAX_REQUESTS_PER_SECOND) * 2;
        assertThat(gameRequests.get(2).receivedNanos() - gameRequests.get(0).receivedNanos())
                .isGreaterThanOrEqualTo(minimumSpacingNanos);
    }

    @Test
    void retriesAThrottledProviderAndThenReportsTheRateLimit() {
        startServer(IgdbFixtureServer.always(429, "{\"message\":\"Too Many Requests\"}"));
        IgdbApiClient client = client(3, Duration.ofMillis(1));

        assertThatThrownBy(() -> client.query("games", "fields id;"))
                .isInstanceOf(ProviderRequestException.class)
                .satisfies(
                        thrown -> {
                            ProviderRequestException exception = (ProviderRequestException) thrown;
                            assertThat(exception.code())
                                    .isEqualTo(ProviderFailureCode.PROVIDER_RATE_LIMITED);
                            assertThat(exception.statistics().retries()).isEqualTo(2);
                            assertThat(exception.getMessage()).doesNotContain("Too Many Requests");
                        });
        assertThat(requests(GAMES_PATH)).hasSize(3);
    }

    @Test
    void retriesAnUnavailableProviderAndThenServesTheRecoveredResponse() {
        startServer(
                IgdbFixtureServer.sequence(
                        new IgdbFixtureServer.Response(503, "unavailable"),
                        new IgdbFixtureServer.Response(200, "[]")));
        IgdbApiClient client = client(3, Duration.ofMillis(1));

        IgdbApiClient.ApiResponse response = client.query("games", "fields id;");

        assertThat(response.body()).isEqualTo("[]");
        assertThat(response.statistics().retries()).isEqualTo(1);
        assertThat(response.statistics().requests()).isEqualTo(3);
    }

    @Test
    void refreshesAnExpiredTokenOnceBeforeReportingAnAuthenticationFailure() {
        startServer(IgdbFixtureServer.always(401, "{\"message\":\"Unauthorized\"}"));
        IgdbApiClient client = client(3, Duration.ofMillis(1));

        assertThatThrownBy(() -> client.query("games", "fields id;"))
                .isInstanceOf(ProviderRequestException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ProviderRequestException) thrown).code())
                                        .isEqualTo(
                                                ProviderFailureCode
                                                        .PROVIDER_AUTHENTICATION_FAILED));
        assertThat(requests(TOKEN_PATH)).hasSize(2);
        assertThat(requests(GAMES_PATH)).hasSize(2);
    }

    @Test
    void reportsAnAuthenticationFailureWithoutRevealingTheCredentialExchange() {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                TOKEN_PATH,
                                IgdbFixtureServer.always(400, "{\"message\":\"invalid client\"}"),
                                GAMES_PATH,
                                IgdbFixtureServer.always(200, "[]")));
        IgdbApiClient client = client(3, Duration.ofMillis(1));

        assertThatThrownBy(() -> client.query("games", "fields id;"))
                .isInstanceOf(ProviderRequestException.class)
                .hasMessage("PROVIDER_AUTHENTICATION_FAILED")
                .satisfies(
                        thrown -> assertThat(thrown.getMessage()).doesNotContain("fixture-secret"));
        assertThat(requests(GAMES_PATH)).isEmpty();
    }

    private void startServer(
            java.util.function.Function<Integer, IgdbFixtureServer.Response> games) {
        server =
                IgdbFixtureServer.start(
                        Map.of(
                                TOKEN_PATH,
                                IgdbFixtureServer.always(200, TOKEN_FIXTURE),
                                GAMES_PATH,
                                games));
    }

    private List<IgdbFixtureServer.RecordedRequest> requests(String path) {
        return server.requests().stream().filter(request -> path.equals(request.path())).toList();
    }

    private IgdbApiClient client(double requestsPerSecond, Duration backoff) {
        IgdbApiSettings settings =
                new IgdbApiSettings(
                        "fixture-client",
                        "fixture-secret",
                        server.uri(TOKEN_PATH),
                        server.uri("/v4/"),
                        Duration.ofSeconds(5),
                        requestsPerSecond,
                        2,
                        backoff,
                        25,
                        Map.of("ps5", "playstation-5"),
                        Map.of("europe", "europe"),
                        "unknown");
        return new IgdbApiClient(
                HttpClient.newHttpClient(),
                JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(),
                settings);
    }
}
