package com.videogameplatform.tools.igdb.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class QueryLoader {

    public String load(String resourceName) {
        String path = "queries/" + resourceName;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new PocException("Query resource not found: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PocException("Cannot read query resource: " + path, exception);
        }
    }

    public String gameSearch(String searchQuery) {
        return load("search-game.apicalypse")
                .replace("{{search_query}}", escape(searchQuery));
    }

    public String gameDetails(long gameId) {
        return load("game-details.apicalypse")
                .replace("{{game_id}}", Long.toString(gameId));
    }

    public String releaseDates(long gameId) {
        return load("release-dates.apicalypse")
                .replace("{{game_id}}", Long.toString(gameId));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
