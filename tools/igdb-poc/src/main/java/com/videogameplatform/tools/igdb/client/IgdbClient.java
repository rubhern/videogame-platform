package com.videogameplatform.tools.igdb.client;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.videogameplatform.tools.igdb.support.PocException;

public final class IgdbClient {

    private static final URI BASE_URI = URI.create("https://api.igdb.com/v4/");

    private final HttpCallExecutor executor;
    private final RequestRateLimiter rateLimiter;

    public IgdbClient(HttpCallExecutor executor, RequestRateLimiter rateLimiter) {
        this.executor = executor;
        this.rateLimiter = rateLimiter;
    }

    public String queryGames(String clientId, String accessToken, String query) {
        return query("games", clientId, accessToken, query);
    }

    public String queryReleaseDates(String clientId, String accessToken, String query) {
        return query("release_dates", clientId, accessToken, query);
    }

    private String query(String endpoint, String clientId, String accessToken, String query) {
        HttpRequest request = HttpRequest.newBuilder(BASE_URI.resolve(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Client-ID", clientId)
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();
        HttpResponse<String> response = executor.execute(request, rateLimiter::acquire);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PocException("IGDB " + endpoint + " request failed with HTTP "
                    + response.statusCode());
        }
        return response.body();
    }
}
