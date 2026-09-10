package com.videogameplatform.identity.adapter.session;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * The minimum, tamper-resistant information needed to resume a rating journey after authentication.
 *
 * <p>It lives only inside the server-side application session, so the browser never carries the
 * selected value or the intended game. It preserves the game the visitor was rating and the value
 * they selected, plus the moment it was issued so a stale context can be rejected.
 */
public record RatingReturnContext(String gameId, String slug, int value, Instant issuedAt)
        implements Serializable {

    public RatingReturnContext {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
