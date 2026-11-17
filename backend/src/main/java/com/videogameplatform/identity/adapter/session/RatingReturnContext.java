package com.videogameplatform.identity.adapter.session;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The minimum, tamper-resistant information needed to resume a rating journey after authentication.
 *
 * <p>The server-side application session preserves the game and the selected general score or
 * platform scores during authentication. The issue time bounds how long it can be recovered.
 */
public record RatingReturnContext(
        String gameId, String slug, Integer value, List<PlatformScore> platforms, Instant issuedAt)
        implements Serializable {

    public RatingReturnContext {
        Objects.requireNonNull(gameId, "gameId");
        platforms = List.copyOf(platforms);
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    public RatingReturnContext(String gameId, String slug, int value, Instant issuedAt) {
        this(gameId, slug, value, List.of(), issuedAt);
    }

    public record PlatformScore(String platformId, Integer value) implements Serializable {}
}
