package com.videogameplatform.ratings.domain;

import java.util.Objects;

/**
 * A platform the user played a rated game on, with the user's own score there when given. A
 * platform played without a score never takes part in any mean.
 */
public record PlatformScore(String platformId, RatingValue value) {
    public PlatformScore {
        Objects.requireNonNull(platformId, "platformId");
        if (platformId.isBlank()) {
            throw new IllegalArgumentException("Platform identifier must not be blank");
        }
    }

    public boolean scored() {
        return value != null;
    }
}
