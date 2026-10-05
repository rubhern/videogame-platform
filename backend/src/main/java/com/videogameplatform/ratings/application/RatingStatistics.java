package com.videogameplatform.ratings.application;

import java.math.BigDecimal;
import java.util.List;

/** Bounded statistics result; unknown aggregate state never masquerades as zero ratings. */
public sealed interface RatingStatistics {
    record Available(BigDecimal mean, int count, List<Integer> distribution)
            implements RatingStatistics {
        public Available {
            distribution = List.copyOf(distribution);
            if (distribution.size() != 10
                    || distribution.stream().anyMatch(n -> n < 0)
                    || distribution.stream().mapToLong(Integer::longValue).sum() != count
                    || (count == 0) != (mean == null)) {
                throw new IllegalArgumentException("Invalid rating statistics");
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
