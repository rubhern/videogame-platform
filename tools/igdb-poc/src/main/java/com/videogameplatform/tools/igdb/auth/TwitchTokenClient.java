package com.videogameplatform.tools.igdb.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.client.HttpCallExecutor;
import com.videogameplatform.tools.igdb.support.PocException;

public final class TwitchTokenClient {

    private static final URI TOKEN_URI = URI.create("https://id.twitch.tv/oauth2/token");

    private final HttpCallExecutor executor;
    private final ObjectMapper objectMapper;

    public TwitchTokenClient(HttpCallExecutor executor, ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public String getAppAccessToken(String clientId, String clientSecret) {
        requireCredential("IGDB_CLIENT_ID", clientId);
        requireCredential("IGDB_CLIENT_SECRET", clientSecret);

        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(TOKEN_URI)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = executor.execute(request, () -> { });
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PocException("Twitch authentication failed with HTTP " + response.statusCode());
        }
        try {
            JsonNode payload = objectMapper.readTree(response.body());
            String token = payload.path("access_token").asText("");
            if (token.isBlank()) {
                throw new PocException("Twitch authentication response did not contain an access token");
            }
            return token;
        } catch (PocException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PocException("Cannot parse Twitch authentication response", exception);
        }
    }

    private void requireCredential(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
