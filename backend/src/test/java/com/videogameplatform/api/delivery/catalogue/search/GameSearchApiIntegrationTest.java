package com.videogameplatform.api.delivery.catalogue.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
@Import(GameSearchApiIntegrationTest.FixedClockConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class GameSearchApiIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("game_search_api");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OpenApiResponseContract OPENAPI = OpenApiResponseContract.load("/games");

    @LocalServerPort private int port;

    @LocalManagementPort private int managementPort;

    @Autowired private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE_NAME, true);
        registry.add("catalogue.releases.freshness-threshold", () -> "P1D");
    }

    @Test
    void returnsARankedContractShapedPageWithPublicHeadersAndAConditionalRead() throws Exception {
        HttpResponse<String> response = search("resident evil");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());

        OPENAPI.assertJsonResponse(response, 200, "GameSearchPage");
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control"))
                .contains("public, max-age=60, stale-while-revalidate=300");
        String entityTag = response.headers().firstValue("ETag").orElseThrow();
        assertThat(entityTag).matches("\"[0-9a-f]{64}\"");

        assertThat(body.path("items")).hasSize(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("canonicalTitle").stringValue()).isEqualTo("Resident Evil Requiem");
        assertThat(item.path("slug").stringValue()).isEqualTo("resident-evil-requiem");
        assertThat(item.path("gameId").stringValue()).isNotBlank();
        assertThat(item.path("primaryCover").path("kind").stringValue()).isEqualTo("fallback");
        assertThat(item.path("releaseContext")).isNotEmpty();
        JsonNode context = item.path("releaseContext").get(0);
        assertThat(context.path("platform").path("name").stringValue()).isNotBlank();
        assertThat(context.path("releaseDate").path("precision").stringValue()).isEqualTo("day");
        assertThat(context.path("freshnessStatus").stringValue()).isEqualTo("stale");
        assertThat(body.path("page").path("number").asInt()).isEqualTo(1);
        assertThat(body.path("page").path("size").asInt()).isEqualTo(20);
        assertThat(body.path("page").path("totalItems").asLong()).isEqualTo(1);
        assertThat(body.path("page").path("totalPages").asLong()).isEqualTo(1);

        HttpResponse<String> notModified =
                search("resident evil", "If-None-Match", "W/" + entityTag);
        OPENAPI.assertEmptyResponse(notModified, 304);
        assertThat(notModified.headers().firstValue("ETag")).contains(entityTag);
    }

    @Test
    void matchesAnApprovedAliasAndReportsTheMatchContext() throws Exception {
        JsonNode body = json(search("the witcher 4"));

        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).path("canonicalTitle").stringValue())
                .isEqualTo("The Witcher IV");
        assertThat(body.path("items").get(0).path("matchedAlias").stringValue())
                .isEqualTo("The Witcher 4");
    }

    @Test
    void omitsTheMatchContextWhenNoApprovedAliasWasInvolved() throws Exception {
        JsonNode body = json(search("pragmata"));

        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).has("matchedAlias")).isFalse();
    }

    @Test
    void matchesPartialQueriesCaseAndDiacriticInsensitively() throws Exception {
        assertThat(json(search("YOTEI")).path("items").get(0).path("canonicalTitle").stringValue())
                .isEqualTo("Ghost of Yōtei");
        assertThat(
                        json(search("hollow kni"))
                                .path("items")
                                .get(0)
                                .path("canonicalTitle")
                                .stringValue())
                .isEqualTo("Hollow Knight: Silksong");
    }

    @Test
    void keepsAmbiguousMatchesAsSeparateResultsInADeterministicOrder() throws Exception {
        JsonNode body = json(search("2"));

        List<String> titles = textValues(body.path("items"), "canonicalTitle");
        assertThat(titles).containsExactly("Death Stranding 2: On the Beach", "Subnautica 2");
        assertThat(textValues(body.path("items"), "gameId")).doesNotHaveDuplicates();
        assertThat(textValues(json(search("2")).path("items"), "gameId"))
                .isEqualTo(textValues(body.path("items"), "gameId"));
    }

    @Test
    void pagesDeterministicallyAndReturnsAnEmptyPageBeyondTheLastOne() throws Exception {
        JsonNode all = json(search("b", 1, 20));
        JsonNode first = json(search("b", 1, 2));
        JsonNode second = json(search("b", 2, 2));
        JsonNode beyond = json(search("b", 50, 20));

        assertThat(all.path("page").path("totalItems").asLong()).isEqualTo(4);
        assertThat(all.path("page").path("totalPages").asLong()).isEqualTo(1);
        assertThat(first.path("page").path("totalPages").asLong()).isEqualTo(2);
        assertThat(textValues(first.path("items"), "gameId"))
                .isEqualTo(textValues(all.path("items"), "gameId").subList(0, 2));
        assertThat(textValues(second.path("items"), "gameId"))
                .isEqualTo(textValues(all.path("items"), "gameId").subList(2, 4));
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("page").path("totalItems").asLong()).isEqualTo(4);
    }

    @Test
    void returnsAnEmptyPageForATitleOutsideTheBoundedCatalogueWithoutCallingAProvider()
            throws Exception {
        HttpResponse<String> response = search("elden ring");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());

        OPENAPI.assertJsonResponse(response, 200, "GameSearchPage");
        assertThat(body.path("items")).isEmpty();
        assertThat(body.path("page").path("totalItems").asLong()).isZero();
        assertThat(body.path("page").path("totalPages").asLong()).isZero();
    }

    @Test
    void neverSurfacesAProviderIdentifierOrRawProviderRecord() throws Exception {
        // Crimson Desert is the seeded game holding an approved IGDB image reference.
        String payload = search("crimson desert").body();

        assertThat(payload).doesNotContain("co7fbz").doesNotContain("igdb");
        JsonNode item = OBJECT_MAPPER.readTree(payload).path("items").get(0);
        assertThat(item.path("gameId").stringValue()).isNotBlank();
        assertThat(item.path("primaryCover").path("kind").stringValue()).isEqualTo("fallback");
        assertThat(item.path("primaryCover").path("attribution").isNull()).isTrue();
    }

    @Test
    void ignoresAliasesThatAreNotApproved() throws Exception {
        assertThat(json(search("samus")).path("page").path("totalItems").asLong()).isZero();
    }

    @Test
    void returnsTheStableProblemForBlankAndInvalidQueries() throws Exception {
        assertProblem(get("/api/v1/games"), 400, "REQUEST_MALFORMED");
        assertProblem(search(" "), 422, "SEARCH_QUERY_INVALID");
        assertProblem(search("!!!"), 422, "SEARCH_QUERY_INVALID");
        assertProblem(search("a".repeat(101)), 422, "SEARCH_QUERY_INVALID");
        assertProblem(get("/api/v1/games?q=evil&q=other"), 422, "SEARCH_QUERY_INVALID");
        assertProblem(get("/api/v1/games?q=evil&pageSize=101"), 422, "PAGINATION_INVALID");
        assertProblem(get("/api/v1/games?q=evil&page=0"), 422, "PAGINATION_INVALID");
        assertProblem(get("/api/v1/games?q=evil&page=1&page=2"), 422, "PAGINATION_INVALID");
        assertProblem(
                get("/api/v1/games?q=evil&unexpected=true"), 422, "REQUEST_PARAMETER_UNKNOWN");
    }

    @Test
    void acceptsSupplementaryLettersUpToTheUnicodeCodePointBound() throws Exception {
        OPENAPI.assertJsonResponse(search("𐐀".repeat(100)), 200, "GameSearchPage");
        assertProblem(search("𐐀".repeat(101)), 422, "SEARCH_QUERY_INVALID");
    }

    @Test
    void countsSearchOutcomesWithoutRecordingAnyQueryContent() throws Exception {
        Counter zeroResults = counter("zero_results");
        Counter results = counter("results");
        double zeroResultsBefore = zeroResults.count();
        double resultsBefore = results.count();

        search("una consulta sin resultados");
        search("pragmata");

        assertThat(zeroResults.count()).isEqualTo(zeroResultsBefore + 1);
        assertThat(results.count()).isEqualTo(resultsBefore + 1);
        assertThat(
                        meterRegistry.find("catalogue.search.result.outcome").meters().stream()
                                .flatMap(meter -> meter.getId().getTags().stream())
                                .map(io.micrometer.core.instrument.Tag::getValue))
                .containsOnly("zero_results", "results");

        JsonNode meters = managementJson("/actuator/metrics");
        assertThat(textValues(meters.path("names"))).contains("catalogue.search.result.outcome");
    }

    @Test
    void leavesTheUndeliveredGameDetailsResourceAbsent() throws Exception {
        HttpResponse<String> response = get("/api/v1/games/game_example");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }

    private Counter counter(String outcome) {
        return meterRegistry
                .find("catalogue.search.result.outcome")
                .tag("outcome", outcome)
                .counter();
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

    private static List<String> textValues(JsonNode items, String property) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.path(property).stringValue()));
        return values;
    }

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.stringValue()));
        return result;
    }

    private HttpResponse<String> search(String query) throws IOException, InterruptedException {
        return get("/api/v1/games?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    private HttpResponse<String> search(String query, String header, String value)
            throws IOException, InterruptedException {
        return get(
                "/api/v1/games?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
                header,
                value);
    }

    private HttpResponse<String> search(String query, int page, int pageSize)
            throws IOException, InterruptedException {
        return get(
                "/api/v1/games?q=%s&page=%d&pageSize=%d"
                        .formatted(
                                URLEncoder.encode(query, StandardCharsets.UTF_8), page, pageSize));
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
