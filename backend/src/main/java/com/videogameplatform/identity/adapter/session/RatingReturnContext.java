package com.videogameplatform.identity.adapter.session;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The minimum, tamper-resistant information needed to resume a rating journey after authentication.
 *
 * <p>The server-side application session preserves the game and the selected general score or
 * platform scores during authentication. The issue time bounds how long it can be recovered. A
 * rating started from a list (#574) also keeps that list's allowlisted local path, so the journey
 * returns there instead of to the game page; it is null for a rating started on the game page.
 */
public record RatingReturnContext(
        String gameId,
        String slug,
        Integer value,
        List<PlatformScore> platforms,
        Instant issuedAt,
        String listPath)
        implements Serializable {

    public RatingReturnContext {
        Objects.requireNonNull(gameId, "gameId");
        platforms = List.copyOf(platforms);
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    public RatingReturnContext(
            String gameId,
            String slug,
            Integer value,
            List<PlatformScore> platforms,
            Instant issuedAt) {
        this(gameId, slug, value, platforms, issuedAt, null);
    }

    public RatingReturnContext(String gameId, String slug, int value, Instant issuedAt) {
        this(gameId, slug, value, List.of(), issuedAt, null);
    }

    public record PlatformScore(String platformId, Integer value) implements Serializable {}
}
