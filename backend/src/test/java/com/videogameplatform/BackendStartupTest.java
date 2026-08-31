package com.videogameplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ActiveProfiles("structured")
@ExtendWith(OutputCaptureExtension.class)
class BackendStartupTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @LocalServerPort private int port;

    @LocalManagementPort private int managementPort;

    @Autowired private BuildProperties buildProperties;

    @Value("${management.tracing.sampling.probability}")
    private double tracingSamplingProbability;

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
    void exposesSafeHealthVersionAndBaselineMetrics(CapturedOutput output)
            throws IOException, InterruptedException {
        assertThat(Runtime.version().feature()).isEqualTo(25);
        assertThat(tracingSamplingProbability).isEqualTo(1.0);

        String samplingCorrelationId = "baseline-sampling-check";
        var sampledProductRequest = get("/api/v1/session", samplingCorrelationId);
        var aggregateHealth = managementGet("/actuator/health");
        var liveness = managementGet("/actuator/health/liveness");
        var readiness = managementGet("/actuator/health/readiness");
        var info = managementGet("/actuator/info");
        var metricNames = managementGet("/actuator/metrics");
        var httpMetrics = managementGet("/actuator/metrics/http.server.requests");

        assertThat(sampledProductRequest.statusCode()).isEqualTo(200);
        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(404);

        assertThat(aggregateHealth.statusCode()).isEqualTo(200);
        assertThat(aggregateHealth.body())
                .contains("\"status\":\"UP\"")
                .contains("\"liveness\"")
                .contains("\"readiness\"")
                .doesNotContain("components", "database", "jdbc:");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"UP\"}");

        String sourceRevision = buildProperties.get("sourceRevision");
        assertThat(sourceRevision).matches("[A-Za-z0-9._-]{1,64}");
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.body())
                .contains("\"version\":\"" + buildProperties.getVersion() + "\"")
                .contains("\"sourceRevision\":\"" + sourceRevision + "\"")
                .doesNotContain("password", "token", "jdbc:");

        assertThat(metricNames.statusCode()).isEqualTo(200);
        assertThat(metricNames.body())
                .contains("http.server.requests")
                .contains("jvm.memory.used")
                .contains("jdbc.connections.active");
        assertThat(httpMetrics.statusCode()).isEqualTo(200);
        assertThat(httpMetrics.body())
                .contains("\"tag\":\"uri\"")
                .doesNotContain("X-Correlation-ID", "traceparent");

        var sampledRootTrace = structuredAccessLog(output, samplingCorrelationId);
        assertThat(sampledRootTrace.path("traceId").stringValue()).matches("[0-9a-f]{32}");
        assertThat(sampledRootTrace.path("spanId").stringValue()).matches("[0-9a-f]{16}");
    }

    @Test
    void correlatesW3cTraceContextWithoutRecordingSensitiveRequestData(CapturedOutput output)
            throws IOException, InterruptedException {
        String correlationId = "issue-23-smoke-correlation";
        String privatePath = "/missing/private-user-123?token=query-secret";
        var request =
                HttpRequest.newBuilder()
                        .uri(URI.create(applicationUrl() + privatePath))
                        .header("Authorization", "Bearer telemetry-secret")
                        .header("Cookie", "SESSION=private-cookie")
                        .header("traceparent", TRACEPARENT)
                        .header("X-Correlation-ID", correlationId)
                        .GET()
                        .build();

        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlationId);
        var accessLog = structuredAccessLog(output, correlationId);
        assertThat(accessLog.path("message").stringValue()).isEqualTo("HTTP request completed");
        assertThat(accessLog.path("correlationId").stringValue()).isEqualTo(correlationId);
        assertThat(accessLog.path("traceId").stringValue()).isEqualTo(TRACE_ID);
        assertThat(accessLog.path("spanId").stringValue()).matches("[0-9a-f]{16}");
        assertThat(accessLog.path("http").path("method").stringValue()).isEqualTo("GET");
        assertThat(accessLog.path("http").path("route").stringValue()).isEqualTo("/**");
        assertThat(accessLog.path("http").path("status_code").asInt()).isEqualTo(404);
        assertThat(accessLog.path("http").path("outcome").stringValue()).isEqualTo("CLIENT_ERROR");
        assertThat(accessLog.path("duration_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(accessLog.toString())
                .doesNotContain(
                        "private-user-123", "query-secret", "telemetry-secret", "private-cookie");

        var httpMetrics = managementGet("/actuator/metrics/http.server.requests");
        assertThat(httpMetrics.body())
                .contains("\"tag\":\"uri\"", "\"/**\"", "CLIENT_ERROR")
                .doesNotContain("private-user-123", correlationId, TRACE_ID);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder().uri(URI.create(applicationUrl() + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String correlationId)
            throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder()
                        .uri(URI.create(applicationUrl() + path))
                        .header("X-Correlation-ID", correlationId)
                        .GET()
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> managementGet(String path)
            throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder().uri(URI.create(managementUrl() + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode structuredAccessLog(CapturedOutput output, String correlationId) {
        String logLine =
                output.getAll()
                        .lines()
                        .filter(line -> line.contains("\"message\":\"HTTP request completed\""))
                        .filter(
                                line ->
                                        line.contains(
                                                "\"correlationId\":\"" + correlationId + "\""))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("Structured access log was not found"));
        return OBJECT_MAPPER.readTree(logLine);
    }

    private String applicationUrl() {
        return "http://localhost:%d".formatted(port);
    }

    private String managementUrl() {
        return "http://localhost:%d".formatted(managementPort);
    }
}
