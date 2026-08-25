package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReleaseApiIntegrationTest.FixedClockConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class ReleaseApiIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("release_api");
    private static final String PLATFORM_PS5 = "10000000-0000-4000-8000-000000000001";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OpenApiResponseContract OPENAPI = OpenApiResponseContract.load();

    @LocalServerPort private int port;

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
        assertThat(body.path("items").size()).isEqualTo(2);
        assertThat(body.path("items").get(0).path("canonicalTitle").stringValue())
                .isEqualTo("Pragmata");
        JsonNode firstReleaseDate = body.path("items").get(0).path("release").path("releaseDate");
        assertThat(firstReleaseDate.path("precision").stringValue()).isEqualTo("quarter");
        assertThat(firstReleaseDate.path("value").stringValue()).isEqualTo("2026-Q2");
        JsonNode secondReleaseDate = body.path("items").get(1).path("release").path("releaseDate");
        assertThat(secondReleaseDate.path("precision").stringValue()).isEqualTo("day");
        assertThat(secondReleaseDate.path("value").stringValue()).isEqualTo("2026-02-27");
        assertThat(body.path("items").get(0).path("release").path("freshnessStatus").stringValue())
                .isEqualTo("stale");
        assertThat(body.path("items").get(0).path("primaryCover").path("kind").stringValue())
                .isEqualTo("fallback");
        assertThat(body.path("items").get(0).path("primaryCover").path("attribution").isNull())
                .isTrue();

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
        assertThat(
                        meterRegistry
                                .find("catalogue.releases.requests")
                                .tag("view", "recent")
                                .tag("outcome", "not_modified")
                                .counter())
                .isNotNull();

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
        assertThat(upcoming.path("items")).hasSize(2);
        JsonNode knownDate = upcoming.path("items").get(0).path("release").path("releaseDate");
        assertThat(knownDate.path("precision").stringValue()).isEqualTo("year");
        assertThat(knownDate.path("value").stringValue()).isEqualTo("2027");
        JsonNode unknownDate = upcoming.path("items").get(1).path("release").path("releaseDate");
        assertThat(unknownDate.path("precision").stringValue()).isEqualTo("unknown");
        assertThat(unknownDate.path("value").isNull()).isTrue();

        JsonNode empty = json(get("/api/v1/releases?view=upcoming&platformId=" + PLATFORM_PS5));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("page").path("totalItems").asLong()).isZero();
        assertThat(empty.path("page").path("totalPages").asInt()).isZero();
        assertThat(empty.path("activeFilters").path("platformId").stringValue())
                .isEqualTo(PLATFORM_PS5);
        assertThat(empty.path("availableFilters").path("platforms")).hasSize(4);

        JsonNode beyond = json(get("/api/v1/releases?view=recent&page=99&pageSize=1"));
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("page").path("number").asInt()).isEqualTo(99);
        assertThat(beyond.path("page").path("totalItems").asLong()).isEqualTo(2);
        assertThat(
                        meterRegistry
                                .find("catalogue.releases.requests")
                                .tag("view", "upcoming")
                                .tag("outcome", "empty")
                                .counter())
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

        JsonNode meters = json(get("/actuator/metrics"));
        assertThat(textValues(meters.path("names")))
                .contains(
                        "catalogue.releases.requests",
                        "catalogue.releases.latency",
                        "catalogue.releases.result.count",
                        "catalogue.releases.failures");
        JsonNode failures = json(get("/actuator/metrics/catalogue.releases.failures"));
        assertThat(tagValues(failures, "code"))
                .contains("FILTER_INVALID", "PLATFORM_NOT_SUPPORTED")
                .doesNotContain("not-supported", "X-Correlation-ID");
        assertThat(
                        meterRegistry
                                .find("catalogue.releases.requests")
                                .tag("view", "invalid")
                                .tag("outcome", "validation_error")
                                .counter())
                .isNotNull();
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

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.stringValue()));
        return result;
    }

    private static List<String> tagValues(JsonNode metric, String tagName) {
        for (JsonNode tag : metric.path("availableTags")) {
            if (tagName.equals(tag.path("tag").stringValue())) {
                return textValues(tag.path("values"));
            }
        }
        return List.of();
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
