package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR-0046: a traced search exports its statement spans, but never the visitor's search term,
 * whether from the request URL or from a bound SQL parameter.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "TELEMETRY_OTLP_METRICS_ENABLED=false",
            "TELEMETRY_OTLP_LOGS_ENABLED=false",
            "TELEMETRY_OTLP_TRACES_ENABLED=true",
            "TELEMETRY_TRACING_SAMPLING_PROBABILITY=1.0"
        })
@DirtiesContext
class TraceExportPrivacyIntegrationTest {
    private static final ByteArrayOutputStream EXPORTED = new ByteArrayOutputStream();
    private static final HttpServer COLLECTOR = collector();
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("trace_privacy");

    @LocalServerPort private int port;
    @Autowired private MeterRegistry meters;

    private static HttpServer collector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/v1/traces",
                    exchange -> {
                        byte[] body = exchange.getRequestBody().readAllBytes();
                        synchronized (EXPORTED) {
                            EXPORTED.writeBytes(body);
                        }
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
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
                "TELEMETRY_OTLP_TRACES_ENDPOINT",
                () -> "http://127.0.0.1:" + COLLECTOR.getAddress().getPort() + "/v1/traces");
    }

    @AfterAll
    static void closeCollector() {
        COLLECTOR.stop(0);
    }

    @Test
    void exportsStatementSpansWithoutTheSearchTerm() throws Exception {
        String term =
                "privacymarker"
                        + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/v1/games?q="
                                                                + term))
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        String exported = "";
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && !exported.contains("jdbc.query")) {
            Thread.sleep(250);
            synchronized (EXPORTED) {
                exported = EXPORTED.toString(StandardCharsets.ISO_8859_1);
            }
        }
        // The search ran SQL: its span carries the parameterized statement, never the bound value.
        assertThat(exported).contains("jdbc.query");
        assertThat(exported).doesNotContain(term);
        // Statement observations add spans only, no metric series per statement.
        assertThat(meters.getMeters().stream().map(meter -> meter.getId().getName()).distinct())
                .filteredOn(name -> name.startsWith("jdbc.query"))
                .isEmpty();
    }
}
