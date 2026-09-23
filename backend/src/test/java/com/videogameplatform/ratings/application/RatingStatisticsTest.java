package com.videogameplatform.ratings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import org.junit.jupiter.api.Test;

class RatingStatisticsTest {
    @Test
    void emptyStatisticsHaveNoMeanAndTenZeroBuckets() {
        var empty = new RatingStatistics.Available(null, 0, Collections.nCopies(10, 0));
        assertThat(empty.mean()).isNull();
        assertThat(empty.count()).isZero();
        assertThat(empty.distribution()).containsOnly(0).hasSize(10);
    }

    @Test
    void rejectsInconsistentBuckets() {
        var buckets = Collections.nCopies(10, 0);
        assertThatThrownBy(() -> new RatingStatistics.Available(null, 1, buckets))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
