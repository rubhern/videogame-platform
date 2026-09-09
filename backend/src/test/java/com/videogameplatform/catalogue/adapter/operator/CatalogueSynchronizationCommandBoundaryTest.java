package com.videogameplatform.catalogue.adapter.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.adapter.provider.igdb.IgdbApiSettings;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runtime evidence that the synchronization command lives outside the product boundary.
 *
 * <p>The architecture test proves no product code can reach the provider. This proves the running
 * application does not offer the command on the port a browser talks to, and that an environment
 * without credentials answers with a stable code instead of attempting a provider call.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ActiveProfiles("structured")
class CatalogueSynchronizationCommandBoundaryTest {

    @LocalServerPort private int port;

    @LocalManagementPort private int managementPort;

    @Autowired private IgdbApiSettings providerSettings;

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> PostgreSqlTestDatabase.runtimeUrl("backend_startup"));
        registry.add("spring.datasource.username", PostgreSqlTestDatabase::runtimeUsername);
        registry.add("spring.datasource.password", PostgreSqlTestDatabase::runtimePassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", () -> PostgreSqlTestDatabase.adminUrl("backend_startup"));
        registry.add("spring.flyway.user", PostgreSqlTestDatabase::migratorUsername);
        registry.add("spring.flyway.password", PostgreSqlTestDatabase::migratorPassword);
    }

    @Test
    void offersTheCommandOnlyOnTheManagementPort() throws Exception {
        assertThat(get(port, "/actuator/cataloguesync").statusCode()).isEqualTo(404);
        // The product port has no such handler at all; its BFF chain rejects the write before
        // routing, so the only requirement is that it never succeeds there.
        assertThat(
                        post(
                                        port,
                                        "/actuator/cataloguesync",
                                        "{\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}")
                                .statusCode())
                .isNotEqualTo(200);
        assertThat(get(managementPort, "/actuator/cataloguesync").statusCode()).isEqualTo(200);
    }

    @Test
    void reportsSynchronizationAsDisabledWithoutProviderCredentials() throws Exception {
        HttpResponse<String> response =
                post(
                        managementPort,
                        "/actuator/cataloguesync",
                        "{\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"outcome\":\"SKIPPED\"")
                .contains("\"outcomeCode\":\"SYNCHRONIZATION_DISABLED\"")
                .doesNotContain("clientSecret")
                .doesNotContain("igdb.com")
                .doesNotContain("batchSize")
                .doesNotContain("checkpoint")
                .doesNotContain("candidateGames");
    }

    @Test
    void acceptsTheSingleCommandWithoutAMode() throws Exception {
        assertThat(
                        post(
                                        managementPort,
                                        "/actuator/cataloguesync",
                                        "{\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}")
                                .body())
                .contains("SYNCHRONIZATION_DISABLED");
    }

    @Test
    void rejectsMissingMalformedOrReversedWindows() throws Exception {
        for (String body :
                new String[] {
                    "{}",
                    "{\"from\":\"bad\",\"to\":\"2026-12-31\"}",
                    "{\"from\":\"2027-01-01\",\"to\":\"2026-12-31\"}"
                }) {
            var response = post(managementPort, "/actuator/cataloguesync", body);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("INVALID_SYNCHRONIZATION_WINDOW");
        }
    }

    @Test
    void rejectsACommandABrowserWasTrickedIntoSendingFromAnotherSite() throws Exception {
        HttpResponse<String> crossSite =
                send(
                        HttpRequest.newBuilder(uri(managementPort, "/actuator/cataloguesync"))
                                .header("Content-Type", "application/json")
                                .header("Origin", "https://attacker.example")
                                .header("Sec-Fetch-Site", "cross-site")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}"))
                                .build());

        assertThat(crossSite.statusCode()).isEqualTo(403);
        assertThat(crossSite.body()).isEmpty();
    }

    /** The shipped taxonomy allowlist must actually bind, or every release would fail mapping. */
    @Test
    void bindsTheShippedProviderTaxonomyAllowlist() {
        assertThat(providerSettings.platformCodes())
                .containsEntry("ps5", "playstation-5")
                .containsEntry("win", "windows-pc")
                .containsEntry("switch-2", "nintendo-switch-2")
                .containsEntry("series-x-s", "xbox-series")
                .doesNotContainKeys("dos", "linux", "mac");
        assertThat(providerSettings.regionCodes())
                .containsEntry("worldwide", "worldwide")
                .containsEntry("europe", "europe")
                .containsEntry("north america", "north-america")
                .containsEntry("japan", "japan");
        assertThat(providerSettings.unknownRegionCode()).isEqualTo("unknown");
        assertThat(providerSettings.requestsPerSecond())
                .isLessThanOrEqualTo(IgdbApiSettings.MAX_REQUESTS_PER_SECOND);
    }

    private static HttpResponse<String> get(int targetPort, String path)
            throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(targetPort, path)).GET().build());
    }

    private static HttpResponse<String> post(int targetPort, String path, String body)
            throws IOException, InterruptedException {
        return send(
                HttpRequest.newBuilder(uri(targetPort, path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    private static HttpResponse<String> send(HttpRequest request)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static URI uri(int targetPort, String path) {
        return URI.create("http://localhost:" + targetPort + path);
    }
}
