package com.videogameplatform.catalogue.adapter.provider.igdb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A loopback IGDB stand-in driven by recorded fixtures.
 *
 * <p>Tests exercise the real HTTP client, authentication, rate limiting, retries and failure
 * mapping without a credential and without reaching the provider, so CI stays credential-free.
 */
final class IgdbFixtureServer implements AutoCloseable {

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> callCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private IgdbFixtureServer(HttpServer server) {
        this.server = server;
    }

    static IgdbFixtureServer start(Map<String, Function<Integer, Response>> handlers) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IgdbFixtureServer fixture = new IgdbFixtureServer(server);
            handlers.forEach(
                    (path, handler) ->
                            server.createContext(
                                    path,
                                    exchange -> {
                                        int call =
                                                fixture.callCounts
                                                        .computeIfAbsent(
                                                                path, key -> new AtomicInteger())
                                                        .getAndIncrement();
                                        fixture.requests.add(recorded(exchange));
                                        Response response = handler.apply(call);
                                        byte[] body =
                                                response.body().getBytes(StandardCharsets.UTF_8);
                                        exchange.getResponseHeaders()
                                                .add("Content-Type", "application/json");
                                        exchange.sendResponseHeaders(
                                                response.status(), body.length);
                                        exchange.getResponseBody().write(body);
                                        exchange.close();
                                    }));
            server.start();
            return fixture;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String fixture(String name) {
        try (InputStream stream =
                IgdbFixtureServer.class.getResourceAsStream("/provider/igdb/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing IGDB fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Function<Integer, Response> always(int status, String body) {
        return call -> new Response(status, body);
    }

    static Function<Integer, Response> sequence(Response... responses) {
        List<Response> ordered = new ArrayList<>(List.of(responses));
        return call -> ordered.get(Math.min(call, ordered.size() - 1));
    }

    private static RecordedRequest recorded(HttpExchange exchange) {
        try (InputStream body = exchange.getRequestBody()) {
            return new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Client-ID"),
                    new String(body.readAllBytes(), StandardCharsets.UTF_8),
                    System.nanoTime());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Response(int status, String body) {}

    record RecordedRequest(
            String path, String authorization, String clientId, String body, long receivedNanos) {}
}
