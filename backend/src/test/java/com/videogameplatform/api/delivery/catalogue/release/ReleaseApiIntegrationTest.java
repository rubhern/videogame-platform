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
import java.util.Optional;
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
    private static final String PLATFORM_WINDOWS_PC = "10000000-0000-4000-8000-000000000003";
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
    void returnsTheCurrentWeekWithContractHeadersAndConditionalRead() throws Exception {
        HttpResponse<String> response = get("/api/v1/releases");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());

        OPENAPI.assertJsonResponse(response, 200, "ReleasePage");
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control")).contains("public, no-cache");
        String entityTag = response.headers().firstValue("ETag").orElseThrow();
        assertThat(entityTag).matches("\"[0-9a-f]{64}\"");
        assertThat(body.has("view")).isFalse();
        // 13 August 2026 is a Thursday in Madrid: the default range is its Monday-to-Sunday week.
        assertThat(body.path("evaluatedOn").stringValue()).isEqualTo("2026-08-13");
        assertThat(body.path("window").path("from").stringValue()).isEqualTo("2026-08-10");
        assertThat(body.path("window").path("to").stringValue()).isEqualTo("2026-08-16");
        assertThat(body.path("items")).isEmpty();
        assertThat(body.path("page").path("totalItems").asLong()).isZero();

        // Naming the same week explicitly reads the same representation.
        assertThat(
                        get("/api/v1/releases?from=2026-08-10&to=2026-08-16")
                                .headers()
                                .firstValue("ETag"))
                .contains(entityTag);
        // The widest range, wholly in the past, holds the five games released in it.
        HttpResponse<String> quarter = get("/api/v1/releases?from=2026-02-13&to=2026-05-15");
        OPENAPI.assertJsonResponse(quarter, 200, "ReleasePage");
        assertThat(quarter.headers().firstValue("ETag")).isNotEqualTo(Optional.of(entityTag));
        assertThat(titles(json(quarter)))
                .containsExactly(
                        "Resident Evil Requiem",
                        "Subnautica 2",
                        "Metroid Prime 4: Beyond",
                        "Crimson Desert",
                        "Pragmata");

        HttpResponse<String> notModified = get("/api/v1/releases", "If-None-Match", entityTag);
        OPENAPI.assertEmptyResponse(notModified, 304);
        assertThat(notModified.headers().firstValue("ETag")).contains(entityTag);

        DistributionSummary resultCount =
                meterRegistry
                        .find("catalogue.releases.result.count")
                        .tag("sort", "releaseDate")
                        .summary();
        assertThat(resultCount).isNotNull();
        long measurementsBeforeWeakRead = resultCount.count();
        double itemsBeforeWeakRead = resultCount.totalAmount();

        HttpResponse<String> weakNotModified =
                get(
                        "/api/v1/releases",
                        "If-None-Match",
                        "\"different,with,commas\", W/" + entityTag);
        assertThat(weakNotModified.statusCode()).isEqualTo(304);
        assertThat(weakNotModified.body()).isEmpty();
        assertThat(weakNotModified.headers().firstValue("ETag")).contains(entityTag);
        assertThat(resultCount.count()).isEqualTo(measurementsBeforeWeakRead);
        assertThat(resultCount.totalAmount()).isEqualTo(itemsBeforeWeakRead);
        assertThat(meterRegistry.find("catalogue.releases.requests").meters()).isEmpty();

        HttpResponse<String> malformedConditional =
                get("/api/v1/releases", "If-None-Match", "\"unterminated, W/" + entityTag);
        assertThat(malformedConditional.statusCode()).isEqualTo(200);
        assertThat(malformedConditional.headers().firstValue("ETag")).contains(entityTag);
    }

    @Test
    void supportsRangesApproximateDatesOrdersFiltersAndPagination() throws Exception {
        JsonNode september = json(get("/api/v1/releases?from=2026-09-01&to=2026-09-30"));
        assertThat(titles(september)).containsExactly("Marvel's Wolverine");
        assertThat(
                        september
                                .path("items")
                                .get(0)
                                .path("releases")
                                .get(0)
                                .path("releaseDate")
                                .path("precision")
                                .stringValue())
                .isEqualTo("day");

        // A future quarter without the opt-in has no exact day; with it, the months and quarter
        // that have not ended join at their own precision.
        HttpResponse<String> exactResponse = get("/api/v1/releases?from=2026-10-01&to=2026-12-31");
        assertThat(json(exactResponse).path("items")).isEmpty();
        HttpResponse<String> approximateResponse =
                get("/api/v1/releases?from=2026-10-01&to=2026-12-31&includeApproximateDates=true");
        OPENAPI.assertJsonResponse(approximateResponse, 200, "ReleasePage");
        assertThat(approximateResponse.headers().firstValue("ETag"))
                .isNotEqualTo(exactResponse.headers().firstValue("ETag"));
        JsonNode approximate = json(approximateResponse);
        assertThat(titles(approximate)).containsExactly("Crimson Desert", "Subnautica 2");
        JsonNode month =
                approximate.path("items").get(0).path("releases").get(0).path("releaseDate");
        assertThat(month.path("precision").stringValue()).isEqualTo("month");
        assertThat(month.path("value").stringValue()).isEqualTo("2026-10");

        // Across a year end the TBA release never joins, and Hype reorders before the date.
        String yearEnd =
                "/api/v1/releases?from=2026-12-01&to=2027-01-31&includeApproximateDates=true";
        assertThat(titles(json(get(yearEnd))))
                .containsExactly("Subnautica 2", "Fable", "The Witcher IV");
        assertThat(titles(json(get(yearEnd + "&sort=hype"))))
                .containsExactly("The Witcher IV", "Subnautica 2", "Fable");
        assertThat(titles(json(get(yearEnd + "&sort=rating"))))
                .containsExactly("Subnautica 2", "Fable", "The Witcher IV");
        // Without stored external scores the games keep the date order, never a zero score.
        assertThat(titles(json(get(yearEnd + "&sort=externalPlayerScore"))))
                .containsExactly("Subnautica 2", "Fable", "The Witcher IV");
        JsonNode secondByHype = json(get(yearEnd + "&sort=hype&page=2&pageSize=1"));
        assertThat(titles(secondByHype)).containsExactly("Subnautica 2");
        assertThat(secondByHype.path("page").path("totalItems").asLong()).isEqualTo(3);
        assertThat(secondByHype.path("page").path("totalPages").asLong()).isEqualTo(3);
        assertThat(
                        meterRegistry
                                .find("catalogue.releases.result.count")
                                .tag("sort", "hype")
                                .summary())
                .isNotNull();

        // The opt-in changes nothing for a range wholly before today.
        assertThat(
                        titles(
                                json(
                                        get(
                                                "/api/v1/releases?from=2026-06-01&to=2026-06-30&includeApproximateDates=true"))))
                .containsExactly("Pragmata");

        JsonNode empty =
                json(
                        get(
                                "/api/v1/releases?platformIds="
                                        + PLATFORM_PS5
                                        + "&regionIds="
                                        + REGION_JAPAN));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("page").path("totalItems").asLong()).isZero();
        assertThat(empty.path("page").path("totalPages").asInt()).isZero();
        assertThat(textValues(empty.path("activeFilters").path("platformIds")))
                .containsExactly(PLATFORM_PS5);
        assertThat(textValues(empty.path("activeFilters").path("regionIds")))
                .containsExactly(REGION_JAPAN);
        // Available filters are contextual, but a selected valid value stays representable so the
        // visitor can remove it.
        assertThat(ids(empty.path("availableFilters").path("platforms"), "platformId"))
                .contains(PLATFORM_PS5);
        assertThat(ids(empty.path("availableFilters").path("regions"), "regionId"))
                .contains(REGION_JAPAN);
        // A filter option reads the product label of its stable region identity.
        assertThat(empty.path("availableFilters").path("regions"))
                .anySatisfy(
                        region -> {
                            assertThat(region.path("regionId").stringValue())
                                    .isEqualTo(REGION_JAPAN);
                            assertThat(region.path("name").stringValue()).isEqualTo("Japón");
                        });

        // Multi-select accepts several repeated values in one dimension (OR); the strict-query
        // convention does not reject the repetition, and the response echoes both.
        JsonNode multi =
                json(
                        get(
                                "/api/v1/releases?from=2026-02-13&to=2026-05-15&platformIds="
                                        + PLATFORM_PS5
                                        + "&platformIds="
                                        + PLATFORM_WINDOWS_PC));
        assertThat(textValues(multi.path("activeFilters").path("platformIds")))
                .containsExactlyInAnyOrder(PLATFORM_PS5, PLATFORM_WINDOWS_PC);
        assertThat(titles(multi))
                .containsExactly("Resident Evil Requiem", "Crimson Desert", "Pragmata");

        JsonNode beyond = json(get("/api/v1/releases?page=99&pageSize=1"));
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("page").path("number").asInt()).isEqualTo(99);
        assertThat(beyond.path("page").path("totalItems").asLong()).isZero();
    }

    @Test
    void returnsStableValidationProblemsAndBoundedTelemetry() throws Exception {
        assertProblem(get("/api/v1/releases?to=2026-08-31"), 422, "FILTER_INVALID", "/query/from");
        assertProblem(get("/api/v1/releases?from=2026-08-01"), 422, "FILTER_INVALID", "/query/to");
        assertProblem(
                get("/api/v1/releases?from=2026-08-31&to=2026-08-01"),
                422,
                "FILTER_INVALID",
                "/query/to");
        // 93 days, one more than the widest range.
        assertProblem(
                get("/api/v1/releases?from=2026-08-01&to=2026-11-01"),
                422,
                "FILTER_INVALID",
                "/query/to");
        assertThat(get("/api/v1/releases?from=2026-08-01&to=2026-10-31").statusCode())
                .isEqualTo(200);
        assertProblem(
                get("/api/v1/releases?from=agosto&to=2026-08-31"),
                422,
                "FILTER_INVALID",
                "/query/from");
        assertProblem(
                get("/api/v1/releases?from=2026-02-28&to=2026-02-30"),
                422,
                "FILTER_INVALID",
                "/query/to");
        assertProblem(
                get("/api/v1/releases?from=2026-08-01&from=2026-08-02&to=2026-08-10"),
                422,
                "FILTER_INVALID",
                "/query/from");
        assertProblem(get("/api/v1/releases?sort=popularity"), 422, "SORT_INVALID", "/query/sort");
        assertProblem(
                get("/api/v1/releases?sort=hype&sort=rating"), 422, "SORT_INVALID", "/query/sort");
        assertProblem(
                get("/api/v1/releases?view=recent"),
                422,
                "REQUEST_PARAMETER_UNKNOWN",
                "/query/view");
        assertProblem(
                get("/api/v1/releases?weeks=1"), 422, "REQUEST_PARAMETER_UNKNOWN", "/query/weeks");
        assertProblem(get("/api/v1/releases?pageSize=101"), 422, "PAGINATION_INVALID");
        assertProblem(get("/api/v1/releases?page=1&page=2"), 422, "PAGINATION_INVALID");
        assertProblem(
                get("/api/v1/releases?includeApproximateDates=yes"),
                422,
                "FILTER_INVALID",
                "/query/includeApproximateDates");
        assertProblem(
                get("/api/v1/releases?includeApproximateDates=true&includeApproximateDates=false"),
                422,
                "FILTER_INVALID");
        assertProblem(get("/api/v1/releases?unexpected=true"), 422, "REQUEST_PARAMETER_UNKNOWN");
        assertProblem(
                get("/api/v1/releases?platformIds=not-supported"), 422, "PLATFORM_NOT_SUPPORTED");
        // Regions are filtered by stable identity only: a display label is not a filter value.
        assertProblem(get("/api/v1/releases?regionIds=Mundial"), 422, "REGION_NOT_SUPPORTED");

        JsonNode meters = managementJson("/actuator/metrics");
        assertThat(textValues(meters.path("names")))
                .contains("catalogue.releases.result.count")
                .doesNotContain(
                        "catalogue.releases.requests",
                        "catalogue.releases.latency",
                        "catalogue.releases.failures");
        assertThat(meterRegistry.find("catalogue.releases.latency").meters()).isEmpty();
    }

    private void assertProblem(
            HttpResponse<String> response, int status, String code, String pointer) {
        assertProblem(response, status, code);
        assertThat(
                        OBJECT_MAPPER
                                .readTree(response.body())
                                .path("violations")
                                .get(0)
                                .path("pointer")
                                .stringValue())
                .isEqualTo(pointer);
    }

    private static List<String> titles(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(item.path("canonicalTitle").stringValue()));
        return result;
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
