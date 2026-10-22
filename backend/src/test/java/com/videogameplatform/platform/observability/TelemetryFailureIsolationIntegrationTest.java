package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "TELEMETRY_OTLP_METRICS_ENABLED=true",
            "TELEMETRY_OTLP_METRICS_STEP=1s",
            "TELEMETRY_OTLP_TRACES_ENABLED=false"
        })
@DirtiesContext
class TelemetryFailureIsolationIntegrationTest {
    private static final CountDownLatch FAILED_EXPORT = new CountDownLatch(1);
    private static final HttpServer COLLECTOR = collector();
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("telemetry_isolation");

    @LocalServerPort private int port;
    @LocalManagementPort private int managementPort;
    @Autowired private OtlpMeterRegistry registry;

    private static HttpServer collector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/v1/metrics",
                    exchange -> {
                        exchange.getRequestBody()
                                .transferTo(java.io.OutputStream.nullOutputStream());
                        exchange.sendResponseHeaders(503, -1);
                        exchange.close();
                        FAILED_EXPORT.countDown();
                    });
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        PostgreSqlTestDatabase.configureSpringDatabase(properties, DATABASE, true);
        properties.add(
                "TELEMETRY_OTLP_METRICS_ENDPOINT",
                () -> "http://127.0.0.1:" + COLLECTOR.getAddress().getPort() + "/v1/metrics");
    }

    @AfterAll
    static void closeCollector() {
        COLLECTOR.stop(0);
    }

    @Test
    void servesProductReadsAndReadinessDuringRejectedAndUnavailableExports() throws Exception {
        registry.counter("telemetry.isolation.fixture").increment();
        assertThat(FAILED_EXPORT.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(get(port, "/api/v1/releases").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        COLLECTOR.stop(0);
        registry.counter("telemetry.isolation.fixture").increment();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(get(port, "/api/v1/releases").statusCode()).isEqualTo(200);
        }
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
