package com.videogameplatform.api.delivery.catalogue.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.DistributionSummary;
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
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(ReleaseApiIntegrationTest.FixedClockConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class ReleaseApiIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("release_api");
    private static final String PLATFORM_PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String REGION_JAPAN = "20000000-0000-4000-8000-000000000005";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OpenApiResponseContract OPENAPI =
            OpenApiResponseContract.load("/releases");

    @LocalServerPort private int port;

    @LocalManagementPort private int managementPort;

    @Autowired private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE_NAME, true);
        registry.add("catalogue.releases.freshness-threshold", () -> "P1D");
    }

    @Test
    void returnsDeterministicRecentResultsWithContractHeadersAndConditionalRead() throws Exception {
        HttpResponse<String> response = get("/api/v1/releases?view=recent");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());

        OPENAPI.assertJsonResponse(response, 200, "ReleasePage");
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control"))
                .contains("public, max-age=60, stale-while-revalidate=300");
        String entityTag = response.headers().firstValue("ETag").orElseThrow();
        assertThat(entityTag).matches("\"[0-9a-f]{64}\"");
        assertThat(body.path("view").stringValue()).isEqualTo("recent");
        assertThat(body.path("evaluatedOn").stringValue()).isEqualTo("2026-08-13");
        assertThat(body.path("window").path("from").stringValue()).isEqualTo("2026-02-13");
        assertThat(body.path("items").size()).isEqualTo(8);
        assertThat(body.path("items").get(0).path("canonicalTitle").stringValue())
                .isEqualTo("Pragmata");
        JsonNode firstReleaseDate = body.path("items").get(0).path("release").path("releaseDate");
        assertThat(firstReleaseDate.path("precision").stringValue()).isEqualTo("quarter");
        assertThat(firstReleaseDate.path("value").stringValue()).isEqualTo("2026-Q2");
        // The same game shares the effective quarter, so releaseId is the only value
        // that separates the two rows.
        assertThat(body.path("items").get(1).path("canonicalTitle").stringValue())
                .isEqualTo("Pragmata");
        assertThat(body.path("items").get(1).path("release").path("releaseId").stringValue())
                .isNotEqualTo(
                        body.path("items").get(0).path("release").path("releaseId").stringValue());
        JsonNode lastReleaseDate = body.path("items").get(7).path("release").path("releaseDate");
        assertThat(body.path("items").get(7).path("canonicalTitle").stringValue())
                .isEqualTo("Resident Evil Requiem");
        assertThat(lastReleaseDate.path("precision").stringValue()).isEqualTo("day");
        assertThat(lastReleaseDate.path("value").stringValue()).isEqualTo("2026-02-27");
        assertThat(body.path("items").get(0).path("release").path("freshnessStatus").stringValue())
                .isEqualTo("stale");
        assertThat(body.path("items").get(0).path("primaryCover").path("kind").stringValue())
                .isEqualTo("fallback");
        assertThat(body.path("items").get(0).path("primaryCover").path("attribution").isNull())
                .isTrue();
        // Crimson Desert carries an approved provider image reference without an
        // attribution URL, so ADR-0001 requires the product-owned fallback.
        JsonNode providerReferenceWithoutAttribution = body.path("items").get(2);
        assertThat(providerReferenceWithoutAttribution.path("canonicalTitle").stringValue())
                .isEqualTo("Crimson Desert");
        assertThat(
                        providerReferenceWithoutAttribution
                                .path("primaryCover")
                                .path("kind")
                                .stringValue())
                .isEqualTo("fallback");
        assertThat(
                        providerReferenceWithoutAttribution
                                .path("primaryCover")
                                .path("alternativeText")
                                .stringValue())
                .isEqualTo("Carátula oficial no disponible");

        HttpResponse<String> notModified =
                get("/api/v1/releases?view=recent", "If-None-Match", entityTag);
        OPENAPI.assertEmptyResponse(notModified, 304);
        assertThat(notModified.headers().firstValue("ETag")).contains(entityTag);

        DistributionSummary resultCount =
                meterRegistry
                        .find("catalogue.releases.result.count")
                        .tag("view", "recent")
                        .summary();
        assertThat(resultCount).isNotNull();
        long measurementsBeforeWeakRead = resultCount.count();
        double itemsBeforeWeakRead = resultCount.totalAmount();

        HttpResponse<String> weakNotModified =
                get(
                        "/api/v1/releases?view=recent",
                        "If-None-Match",
                        "\"different,with,commas\", W/" + entityTag);
        assertThat(weakNotModified.statusCode()).isEqualTo(304);
        assertThat(weakNotModified.body()).isEmpty();
        assertThat(weakNotModified.headers().firstValue("ETag")).contains(entityTag);
        assertThat(resultCount.count()).isEqualTo(measurementsBeforeWeakRead);
        assertThat(resultCount.totalAmount()).isEqualTo(itemsBeforeWeakRead);
        assertThat(meterRegistry.find("catalogue.releases.requests").meters()).isEmpty();

        HttpResponse<String> malformedConditional =
                get(
                        "/api/v1/releases?view=recent",
                        "If-None-Match",
                        "\"unterminated, W/" + entityTag);
        assertThat(malformedConditional.statusCode()).isEqualTo(200);
        assertThat(malformedConditional.headers().firstValue("ETag")).contains(entityTag);
        assertThat(OBJECT_MAPPER.readTree(malformedConditional.body()).path("view").stringValue())
                .isEqualTo("recent");
    }

    @Test
    void supportsFiltersPaginationEmptyPagesAndUnknownDatePrecision() throws Exception {
        JsonNode upcoming = json(get("/api/v1/releases?view=upcoming&page=1&pageSize=20"));
        assertThat(upcoming.path("items")).hasSize(8);
        JsonNode knownDate = upcoming.path("items").get(0).path("release").path("releaseDate");
        assertThat(knownDate.path("precision").stringValue()).isEqualTo("day");
        assertThat(knownDate.path("value").stringValue()).isEqualTo("2026-09-25");
        JsonNode unknownDate = upcoming.path("items").get(7).path("release").path("releaseDate");
        assertThat(unknownDate.path("precision").stringValue()).isEqualTo("unknown");
        assertThat(unknownDate.path("value").isNull()).isTrue();

        JsonNode firstUpcomingPage = json(get("/api/v1/releases?view=upcoming&page=1&pageSize=6"));
        JsonNode lastUpcomingPage = json(get("/api/v1/releases?view=upcoming&page=2&pageSize=6"));
        assertThat(firstUpcomingPage.path("items")).hasSize(6);
        assertThat(firstUpcomingPage.path("page").path("totalPages").asInt()).isEqualTo(2);
        assertThat(lastUpcomingPage.path("items")).hasSize(2);
        assertThat(lastUpcomingPage.path("page").path("totalItems").asLong()).isEqualTo(8);

        JsonNode empty =
                json(
                        get(
                                "/api/v1/releases?view=upcoming&platformId="
                                        + PLATFORM_PS5
                                        + "&regionId="
                                        + REGION_JAPAN));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("page").path("totalItems").asLong()).isZero();
        assertThat(empty.path("page").path("totalPages").asInt()).isZero();
        assertThat(empty.path("activeFilters").path("platformId").stringValue())
                .isEqualTo(PLATFORM_PS5);
        assertThat(empty.path("activeFilters").path("regionId").stringValue())
                .isEqualTo(REGION_JAPAN);
        assertThat(empty.path("availableFilters").path("platforms")).hasSize(4);
        assertThat(empty.path("availableFilters").path("regions")).hasSize(5);

        JsonNode beyond = json(get("/api/v1/releases?view=recent&page=99&pageSize=1"));
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("page").path("number").asInt()).isEqualTo(99);
        assertThat(beyond.path("page").path("totalItems").asLong()).isEqualTo(8);
        assertThat(
                        meterRegistry
                                .find("catalogue.releases.result.count")
                                .tag("view", "upcoming")
                                .summary())
                .isNotNull();
    }

    @Test
    void returnsStableValidationProblemsAndBoundedTelemetry() throws Exception {
        assertProblem(get("/api/v1/releases"), 400, "REQUEST_MALFORMED");
        assertProblem(get("/api/v1/releases?view=invalid"), 422, "FILTER_INVALID");
        assertProblem(get("/api/v1/releases?view=recent&pageSize=101"), 422, "PAGINATION_INVALID");
        assertProblem(get("/api/v1/releases?view=recent&view=upcoming"), 422, "FILTER_INVALID");
        assertProblem(get("/api/v1/releases?view=recent&page=1&page=2"), 422, "PAGINATION_INVALID");
        assertProblem(
                get("/api/v1/releases?view=recent&unexpected=true"),
                422,
                "REQUEST_PARAMETER_UNKNOWN");
        assertProblem(
                get("/api/v1/releases?view=recent&platformId=not-supported"),
                422,
                "PLATFORM_NOT_SUPPORTED");

        JsonNode meters = managementJson("/actuator/metrics");
        assertThat(textValues(meters.path("names")))
                .contains("catalogue.releases.result.count")
                .doesNotContain(
                        "catalogue.releases.requests",
                        "catalogue.releases.latency",
                        "catalogue.releases.failures");
        assertThat(meterRegistry.find("catalogue.releases.latency").meters()).isEmpty();
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        OPENAPI.assertJsonResponse(response, status, "Problem");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(body.path("code").stringValue()).isEqualTo(code);
        assertThat(body.path("correlationId").stringValue()).isNotBlank();
        assertThat(response.headers().firstValue("X-Correlation-ID"))
                .contains(body.path("correlationId").stringValue());
        assertThat(body.path("violations")).hasSize(1);
    }

    private JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return OBJECT_MAPPER.readTree(response.body());
    }

    private JsonNode managementJson(String path) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:%d%s".formatted(managementPort, path)))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        return json(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.stringValue()));
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
