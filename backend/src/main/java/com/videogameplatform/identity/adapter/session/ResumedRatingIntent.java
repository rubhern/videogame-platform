package com.videogameplatform.identity.adapter.session;

import java.io.Serializable;
import java.util.Objects;

/**
 * A single-use rating selection recovered after authentication, bound to the authenticated user.
 *
 * <p>The success handler produces exactly one of these when it consumes a valid return context. The
 * game page reads it once to present the pending, non-persisted selection; the store removes it on
 * that first read so a reload or replay cannot resume the same logical selection again.
 */
public record ResumedRatingIntent(String gameId, String slug, int value, String userId)
        implements Serializable {

    public ResumedRatingIntent {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(userId, "userId");
    }
}
