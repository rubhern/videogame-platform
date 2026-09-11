package com.videogameplatform.ratings.application;

import com.videogameplatform.ratings.domain.RatingValue;
import java.time.Instant;
import java.util.Objects;

/** Current personal rating state with an opaque optimistic-concurrency token. */
public record PersonalRating(
        String gameId, int value, Instant createdAt, Instant updatedAt, String versionToken) {
    public PersonalRating {
        Objects.requireNonNull(gameId, "gameId");
        new RatingValue(value);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(versionToken, "versionToken");
        if (gameId.isBlank() || versionToken.isBlank() || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid personal rating state");
        }
    }
}
