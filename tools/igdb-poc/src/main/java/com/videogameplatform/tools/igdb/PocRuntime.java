package com.videogameplatform.tools.igdb;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.videogameplatform.tools.igdb.auth.TwitchTokenClient;
import com.videogameplatform.tools.igdb.client.HttpCallExecutor;
import com.videogameplatform.tools.igdb.client.IgdbClient;
import com.videogameplatform.tools.igdb.client.RequestRateLimiter;
import com.videogameplatform.tools.igdb.model.ExecutionStats;

public final class PocRuntime {

    private PocRuntime() {
    }

    public static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static Clients clients(
            ObjectMapper objectMapper,
            ExecutionStats stats,
            double requestsPerSecond) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpCallExecutor executor = new HttpCallExecutor(httpClient, stats);
        return new Clients(
                new TwitchTokenClient(executor, objectMapper),
                new IgdbClient(executor, new RequestRateLimiter(requestsPerSecond)));
    }

    public record Clients(TwitchTokenClient tokenClient, IgdbClient igdbClient) {
    }
}
