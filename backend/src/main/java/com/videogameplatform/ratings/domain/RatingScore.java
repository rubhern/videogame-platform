package com.videogameplatform.ratings.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;

/**
 * One rating's score: a direct value, or the scores the user gave the platforms they played on.
 * Exactly one source gives the rating's single community contribution (RAT-012), so a user who
 * scores several platforms still casts one vote; platforms played without a score only describe
 * the experience.
 */
public record RatingScore(RatingValue direct, List<PlatformScore> platforms) {
    /** The most platforms one rating may name. */
    public static final int MAX_PLATFORMS = 50;

    public enum Mode {
        DIRECT,
        PLATFORMS
    }

    public RatingScore {
        platforms = platformSet(platforms);
        if ((direct != null) == platforms.stream().anyMatch(PlatformScore::scored)) {
            throw new InvalidRatingScoreException(
                    InvalidRatingScoreException.Violation.SCORE_SOURCE,
                    "A rating has a direct value exactly when no platform is scored");
        }
    }

    /**
     * A rating's platform set: each platform at most once and at most {@link #MAX_PLATFORMS}.
     *
     * @throws InvalidRatingScoreException with {@code PLATFORMS} otherwise
     */
    public static List<PlatformScore> platformSet(List<PlatformScore> platforms) {
        var identifiers = new HashSet<String>();
        if (platforms.size() > MAX_PLATFORMS
                || !platforms.stream().map(PlatformScore::platformId).allMatch(identifiers::add)) {
            throw new InvalidRatingScoreException(
                    InvalidRatingScoreException.Violation.PLATFORMS,
                    "A rating names each platform once and at most " + MAX_PLATFORMS);
        }
        return List.copyOf(platforms);
    }

    public Mode mode() {
        return direct == null ? Mode.PLATFORMS : Mode.DIRECT;
    }

    public int scoredPlatforms() {
        return (int) platforms.stream().filter(PlatformScore::scored).count();
    }

    public int platformScoreSum() {
        return platforms.stream()
                .filter(PlatformScore::scored)
                .mapToInt(platform -> platform.value().value())
                .sum();
    }

    /**
     * The contribution as exposed (AGG-005): the direct value, or the exact mean of the scored
     * platforms rounded half up to one decimal. Aggregates use the unrounded mean instead.
     */
    public BigDecimal contribution() {
        if (direct != null) {
            return BigDecimal.valueOf(direct.value()).setScale(1);
        }
        return BigDecimal.valueOf(platformScoreSum())
                .divide(BigDecimal.valueOf(scoredPlatforms()), 1, RoundingMode.HALF_UP);
    }
}
