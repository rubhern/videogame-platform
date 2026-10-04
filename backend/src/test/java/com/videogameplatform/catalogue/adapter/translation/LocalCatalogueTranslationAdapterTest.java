package com.videogameplatform.catalogue.adapter.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.videogameplatform.catalogue.application.localization.port.TranslationFailure;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalCatalogueTranslationAdapterTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private String response = "{\"text\":\"Un guerrero.\",\"revision\":\"fixture-v1\"}";
    private int status = 200;
    private long delay;
    private String source;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(
                "/translate",
                exchange -> {
                    source =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private LocalCatalogueTranslationAdapter adapter(Duration timeout) {
        return new LocalCatalogueTranslationAdapter(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/translate"),
                timeout);
    }

    @Test
    void translatesAcrossTheNarrowFixedLanguageBoundary() {
        var result = adapter(Duration.ofSeconds(2)).translate("A warrior.");
        assertThat(result.text()).isEqualTo("Un guerrero.");
        assertThat(result.revision()).isEqualTo("fixture-v1");
        assertThat(source).isEqualTo("{\"text\":\"A warrior.\"}");
    }

    @Test
    void timeoutIsBounded() {
        delay = 1000;
        assertThatThrownBy(() -> adapter(Duration.ofMillis(100)).translate("Source."))
                .isInstanceOf(TranslationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((TranslationFailure) failure).mayStillBeRunning())
                                        .isTrue());
    }

    @Test
    void interruptionPreservesInterruptAndTreatsCompletionAsAmbiguous() {
        var adapter = adapter(Duration.ofSeconds(2));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> adapter.translate("Source."))
                    .isInstanceOf(TranslationFailure.class)
                    .satisfies(
                            failure ->
                                    assertThat(((TranslationFailure) failure).mayStillBeRunning())
                                            .isTrue());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsMalformedAndOversizedOutputAndUnavailableRuntime() {
        response = "{\"text\":2}";
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).translate("Source."))
                .isInstanceOf(RuntimeException.class);
        response = "x".repeat(100001);
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).translate("Source."))
                .isInstanceOf(RuntimeException.class);
        status = 503;
        response = "{}";
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).translate("Source."))
                .isInstanceOf(RuntimeException.class);
    }
}
