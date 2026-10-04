package com.videogameplatform.catalogue.adapter.translation;

import com.videogameplatform.catalogue.application.localization.port.CatalogueTranslationPort;
import com.videogameplatform.catalogue.application.localization.port.TranslationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Private acquisition helper; fixed EN -> ES protocol, no provider or runtime DTO crosses the port. */
public final class LocalCatalogueTranslationAdapter implements CatalogueTranslationPort {
    private static final Logger LOG =
            LoggerFactory.getLogger(LocalCatalogueTranslationAdapter.class);
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();

    public LocalCatalogueTranslationAdapter(HttpClient client, URI endpoint, Duration timeout) {
        if (timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Translation timeout must be in (0,60s]");
        this.client = client;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public Translation translate(String source) {
        if (source.length() > 20000)
            throw new IllegalArgumentException("Translation source bound exceeded");
        var request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(Map.of("text", source))))
                        .build();
        try {
            var response =
                    client.send(
                            request,
                            info ->
                                    HttpResponse.BodySubscribers.limiting(
                                            HttpResponse.BodySubscribers.ofByteArray(), 100000));
            if (response.statusCode() != 200)
                throw new IllegalStateException("Translation helper rejected request");
            var value = json.readTree(response.body());
            if (!value.path("text").isString() || !value.path("revision").isString())
                throw new IllegalStateException("Invalid translation helper response");
            return new Translation(
                    value.path("text").asString(), value.path("revision").asString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TranslationFailure(true, interrupted);
        } catch (IOException | RuntimeException failure) {
            LOG.warn(
                    "Catalogue translation failed reason={}",
                    failure instanceof java.net.http.HttpTimeoutException
                            ? "timeout"
                            : "unavailable_or_invalid");
            throw new TranslationFailure(
                    failure instanceof IOException
                            && !(failure instanceof java.net.ConnectException),
                    failure);
        }
    }
}
