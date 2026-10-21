package com.videogameplatform.ratings.application;

import com.videogameplatform.ratings.domain.PlatformScore;
import com.videogameplatform.ratings.domain.RatingScore;
import com.videogameplatform.ratings.domain.RatingValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Current personal rating state with an opaque optimistic-concurrency token. {@code value} is the
 * direct score, absent when the score is the mean of the scored platforms; platforms are ordered
 * by case-insensitive name then identifier and carry Ratings' own label.
 */
public record PersonalRating(
        String gameId,
        Integer value,
        List<PlayedPlatform> platforms,
        Instant createdAt,
        Instant updatedAt,
        String versionToken) {
    public PersonalRating {
        Objects.requireNonNull(gameId, "gameId");
        platforms = List.copyOf(platforms);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(versionToken, "versionToken");
        if (gameId.isBlank() || versionToken.isBlank() || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid personal rating state");
        }
        score(value, platforms);
    }

    /** A platform the user played on, named by its label, with the user's optional score there. */
    public record PlayedPlatform(String platformId, String name, Integer value) {
        public PlayedPlatform {
            Objects.requireNonNull(platformId, "platformId");
            Objects.requireNonNull(name, "name");
        }
    }

    public RatingScore score() {
        return score(value, platforms);
    }

    public RatingScore.Mode mode() {
        return score().mode();
    }

    /** The rating's one community vote as exposed: half up to one decimal. */
    public BigDecimal contribution() {
        return score().contribution();
    }

    private static RatingScore score(Integer value, List<PlayedPlatform> platforms) {
        return new RatingScore(
                value == null ? null : new RatingValue(value),
                platforms.stream()
                        .map(
                                platform ->
                                        new PlatformScore(
                                                platform.platformId(),
                                                platform.value() == null
                                                        ? null
                                                        : new RatingValue(platform.value())))
                        .toList());
    }
}
