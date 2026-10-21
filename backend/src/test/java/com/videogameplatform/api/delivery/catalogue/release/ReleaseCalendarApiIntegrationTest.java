package com.videogameplatform.api.delivery.catalogue.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP evidence for UC-016 against the deterministic seed on the pinned browser-gate clock. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(ReleaseCalendarApiIntegrationTest.FixedClockConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class ReleaseCalendarApiIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("release_calendar_api");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OpenApiResponseContract OPENAPI =
            OpenApiResponseContract.load("/release-calendar");
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";

    @LocalServerPort private int port;

    @Autowired private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort
            provider;

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE_NAME, true);
    }

    @AfterEach
    void calendarReadsNeverCallTheProvider() {
        Mockito.verify(provider, Mockito.never()).fetchWorks(ArgumentMatchers.any());
        Mockito.verify(provider, Mockito.never())
                .releaseGames(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyInt());
    }

    @Test
    void countsTheCurrentMadridMonthWithContractHeadersAndConditionalRead() throws Exception {
        HttpResponse<String> response = get("/api/v1/release-calendar");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());

        OPENAPI.assertJsonResponse(response, 200, "ReleaseCalendar");
        assertThat(body.path("month").stringValue()).isEqualTo("2026-08");
        assertThat(body.path("evaluatedOn").stringValue()).isEqualTo("2026-08-13");
        assertThat(body.path("window").path("from").stringValue()).isEqualTo("2026-08-01");
        assertThat(body.path("window").path("to").stringValue()).isEqualTo("2026-08-31");
        // 13 August plus the default 180-day horizon is 9 February 2027: January is the last month.
        assertThat(body.path("navigation").path("first").stringValue()).isEqualTo("2026-08");
        assertThat(body.path("navigation").path("last").stringValue()).isEqualTo("2027-01");
        assertThat(body.path("navigation").has("previous")).isFalse();
        assertThat(body.path("navigation").path("next").stringValue()).isEqualTo("2026-09");
        // The seed holds no exact-day release in August: an empty calendar, not an error.
        assertThat(body.path("days")).isEmpty();
        assertThat(json(get("/api/v1/release-calendar?month=2026-08"))).isEqualTo(body);
        assertThat(response.headers().firstValue("Cache-Control")).contains("public, no-cache");

        String entityTag = response.headers().firstValue("ETag").orElseThrow();
        OPENAPI.assertEmptyResponse(
                get("/api/v1/release-calendar", "If-None-Match", entityTag), 304);
        assertThat(get("/api/v1/release-calendar?platformIds=" + PS5).headers().firstValue("ETag"))
                .isNotEqualTo(java.util.Optional.of(entityTag));
    }

    @Test
    void calendarCountsAreASubsetOfTheFullExplorer() throws Exception {
        for (String query : new String[] {"", "&platformIds=" + PS5}) {
            for (String month : new String[] {"2026-08", "2026-09", "2026-10"}) {
                JsonNode calendar = json(get("/api/v1/release-calendar?month=" + month + query));
                Map<String, Long> counts = new HashMap<>();
                calendar.path("days")
                        .forEach(
                                day ->
                                        counts.put(
                                                day.path("date").stringValue(),
                                                day.path("gameCount").longValue()));
                LocalDate first =
                        LocalDate.parse(calendar.path("window").path("from").stringValue());
                LocalDate last = LocalDate.parse(calendar.path("window").path("to").stringValue());
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    JsonNode page =
                            json(
                                    get(
                                            "/api/v1/releases?from=%s&to=%s&sort=hype&pageSize=3%s"
                                                    .formatted(day, day, query)));
                    assertThat(page.path("page").path("totalItems").longValue())
                            .as("games on %s%s", day, query)
                            .isGreaterThanOrEqualTo(counts.getOrDefault(day.toString(), 0L));
                }
            }
        }
    }

    @Test
    void countsOnlyExactDaysFromTodayOn() throws Exception {
        JsonNode september = json(get("/api/v1/release-calendar?month=2026-09"));
        // The seed's Wolverine qualifies at the lower threshold.
        assertThat(september.path("days").findValuesAsString("date")).containsExactly("2026-09-25");
        assertThat(september.path("days").get(0).path("totalHype").longValue()).isEqualTo(61);
        assertThat(
                        json(get("/api/v1/releases?from=2026-09-25&to=2026-09-25"))
                                .path("page")
                                .path("totalItems")
                                .longValue())
                .isEqualTo(1);
        assertThat(september.has("approximateDates")).isFalse();

        // Crimson Desert is known only to October 2026: it is never counted on a day.
        json(get("/api/v1/release-calendar?month=2026-10"))
                .path("days")
                .forEach(day -> assertThat(day.path("date").stringValue()).startsWith("2026-10-"));
        // The current month starts counting on 13 August, the pinned evaluation date.
        json(get("/api/v1/release-calendar"))
                .path("days")
                .forEach(
                        day ->
                                assertThat(day.path("date").stringValue())
                                        .isGreaterThanOrEqualTo("2026-08-13"));
    }

    @Test
    void calendarThresholdNeverRestrictsTheFullExplorer() throws Exception {
        try (var connection = PostgreSqlTestDatabase.adminConnection(DATABASE_NAME);
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE catalogue.game_featured_evidence SET hypes = 10 WHERE game_id = '30000000-0000-4000-8000-00000000000c'");
            try {
                var days = json(get("/api/v1/release-calendar?month=2026-09")).path("days");
                assertThat(days.findValuesAsString("date")).containsExactly("2026-09-25");
                assertThat(days.get(0).path("gameCount").longValue()).isEqualTo(1);
                assertThat(days.get(0).path("totalHype").longValue()).isEqualTo(10);
                var page =
                        json(
                                get(
                                        "/api/v1/releases?from=2026-09-25&to=2026-09-25&sort=hype&pageSize=3"));
                assertThat(page.path("page").path("totalItems").longValue()).isEqualTo(1);
                assertThat(page.path("items").get(0).path("canonicalTitle").stringValue())
                        .isEqualTo("Marvel's Wolverine");
                statement.executeUpdate(
                        "UPDATE catalogue.game_featured_evidence SET hypes = 9 WHERE game_id = '30000000-0000-4000-8000-00000000000c'");
                assertThat(json(get("/api/v1/release-calendar?month=2026-09")).path("days"))
                        .isEmpty();
                assertThat(
                                json(get("/api/v1/releases?from=2026-09-25&to=2026-09-25"))
                                        .path("page")
                                        .path("totalItems")
                                        .longValue())
                        .isEqualTo(1);
            } finally {
                statement.executeUpdate(
                        "UPDATE catalogue.game_featured_evidence SET hypes = 61 WHERE game_id = '30000000-0000-4000-8000-00000000000c'");
            }
        }
        assertProblem(get("/api/v1/releases?featuredOnly=true"), "REQUEST_PARAMETER_UNKNOWN");
    }

    @Test
    void rejectsMonthsOutsideTheBoundsAndInvalidFilters() throws Exception {
        assertPointer(get("/api/v1/release-calendar?month=2026-07"), "/query/month");
        assertPointer(get("/api/v1/release-calendar?month=2027-02"), "/query/month");
        assertPointer(get("/api/v1/release-calendar?month=2026-13"), "/query/month");
        assertProblem(
                get("/api/v1/release-calendar?platformIds=unsupported-platform"),
                "PLATFORM_NOT_SUPPORTED");
        assertProblem(
                get("/api/v1/release-calendar?regionIds=unsupported-region"),
                "REGION_NOT_SUPPORTED");
        assertProblem(get("/api/v1/release-calendar?day=2026-08-13"), "REQUEST_PARAMETER_UNKNOWN");
        assertPointer(get("/api/v1/release-calendar?month=2026-08&month=2026-09"), "/query/month");
        assertThat(
                        json(get("/api/v1/release-calendar?month=2027-01"))
                                .path("navigation")
                                .has("next"))
                .isFalse();
    }

    @Test
    void recordsOnlyBoundedTelemetryDimensions() throws Exception {
        json(get("/api/v1/release-calendar"));
        json(get("/api/v1/release-calendar?month=2026-12&platformIds=" + PS5));

        assertThat(
                        meterRegistry
                                .find(ReleaseCalendarApiMetrics.DAYS)
                                .tags("month", "current")
                                .summary())
                .isNotNull();
        assertThat(
                        meterRegistry
                                .find(ReleaseCalendarApiMetrics.DAYS)
                                .tags("month", "later")
                                .summary())
                .isNotNull();
        meterRegistry
                .find(ReleaseCalendarApiMetrics.DAYS)
                .meters()
                .forEach(
                        meter ->
                                assertThat(meter.getId().getTags())
                                        .extracting(tag -> tag.getKey())
                                        .containsExactly("month"));
    }

    private void assertPointer(HttpResponse<String> response, String pointer) {
        assertProblem(response, "FILTER_INVALID");
        assertThat(
                        OBJECT_MAPPER
                                .readTree(response.body())
                                .path("violations")
                                .get(0)
                                .path("pointer")
                                .stringValue())
                .isEqualTo(pointer);
    }

    private void assertProblem(HttpResponse<String> response, String code) {
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        OPENAPI.assertJsonResponse(response, 422, "Problem");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(body.path("code").stringValue()).isEqualTo(code);
    }

    private JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return OBJECT_MAPPER.readTree(response.body());
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
