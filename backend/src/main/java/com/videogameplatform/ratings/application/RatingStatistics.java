package com.videogameplatform.ratings.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Bounded statistics result; unknown aggregate state never masquerades as zero ratings. */
public sealed interface RatingStatistics {
    /**
     * One vote per rating: {@code count} users, the mean of their unrounded contributions and each
     * contribution's half-up integer bucket. {@code platforms} are the per-platform readings of the
     * explicit platform scores; their counts never add up to voters.
     */
    record Available(
            BigDecimal mean,
            int count,
            List<Integer> distribution,
            List<PlatformStatistics> platforms)
            implements RatingStatistics {
        public Available {
            distribution = List.copyOf(distribution);
            platforms = List.copyOf(platforms);
            if (distribution.size() != 10
                    || distribution.stream().anyMatch(n -> n < 0)
                    || distribution.stream().mapToLong(Integer::longValue).sum() != count
                    || (count == 0) != (mean == null)
                    || (count == 0 && !platforms.isEmpty())) {
                throw new IllegalArgumentException("Invalid rating statistics");
            }
        }
    }

    /** The mean of one platform's explicit scores and the number of users who gave one. */
    record PlatformStatistics(String platformId, String name, BigDecimal mean, int count) {
        public PlatformStatistics {
            Objects.requireNonNull(platformId, "platformId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mean, "mean");
            if (count < 1
                    || mean.compareTo(BigDecimal.ONE) < 0
                    || mean.compareTo(BigDecimal.TEN) > 0) {
                throw new IllegalArgumentException("Invalid platform rating statistics");
            }
        }
    }

    record Summary(BigDecimal mean, int count) {
        public Summary {
            if (count < 0
                    || (count == 0) != (mean == null)
                    || (mean != null
                            && (mean.compareTo(BigDecimal.ONE) < 0
                                    || mean.compareTo(BigDecimal.TEN) > 0)))
                throw new IllegalArgumentException("Invalid rating summary");
        }
    }

    record Unavailable() implements RatingStatistics {}
}
