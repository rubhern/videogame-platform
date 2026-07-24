package com.videogameplatform.tools.igdb;

import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.model.ExecutionStats;
import com.videogameplatform.tools.igdb.support.PocException;
import com.videogameplatform.tools.igdb.support.QueryLoader;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "smoke",
        description = "Validate OAuth, one IGDB request and JSON parsing.")
public final class SmokeCommand implements Callable<Integer> {

    @Option(
            names = "--requests-per-second",
            defaultValue = "3",
            description = "Maximum IGDB requests per second (must be <= 3).")
    private double requestsPerSecond;

    @Override
    public Integer call() throws Exception {
        String clientId = System.getenv("IGDB_CLIENT_ID");
        String clientSecret = System.getenv("IGDB_CLIENT_SECRET");
        ObjectMapper objectMapper = PocRuntime.objectMapper();
        ExecutionStats stats = new ExecutionStats();
        PocRuntime.Clients clients = PocRuntime.clients(objectMapper, stats, requestsPerSecond);

        String token = clients.tokenClient().getAppAccessToken(clientId, clientSecret);
        String response = clients.igdbClient().queryGames(
                clientId, token, new QueryLoader().load("smoke.apicalypse"));
        JsonNode payload = objectMapper.readTree(response);
        stats.complete();
        if (!payload.isArray() || payload.isEmpty()) {
            throw new PocException("Smoke request returned no game result");
        }

        System.out.println("Authentication: PASS");
        System.out.println("Token received: yes");
        System.out.println("Token printed: no");
        System.out.println("IGDB request: PASS");
        System.out.println("Response parsed: yes");
        System.out.println("Requests recorded: " + stats.requestCount());
        return 0;
    }
}
