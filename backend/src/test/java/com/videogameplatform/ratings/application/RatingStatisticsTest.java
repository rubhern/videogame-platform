package com.videogameplatform.ratings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RatingStatisticsTest {
    @Test
    void emptyStatisticsHaveNoMeanAndTenZeroBuckets() {
        var empty = new RatingStatistics.Available(null, 0, Collections.nCopies(10, 0), List.of());
        assertThat(empty.mean()).isNull();
        assertThat(empty.count()).isZero();
        assertThat(empty.distribution()).containsOnly(0).hasSize(10);
    }

    @Test
    void rejectsInconsistentBuckets() {
        var buckets = Collections.nCopies(10, 0);
        assertThatThrownBy(() -> new RatingStatistics.Available(null, 1, buckets, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformReadingsNeedVotersAndAPositiveCountInsideTheScale() {
        var buckets = new java.util.ArrayList<>(Collections.nCopies(10, 0));
        buckets.set(8, 1);
        var ps5 =
                new RatingStatistics.PlatformStatistics("ps5", "PlayStation 5", BigDecimal.TEN, 1);
        assertThat(
                        new RatingStatistics.Available(BigDecimal.TEN, 1, buckets, List.of(ps5))
                                .platforms())
                .containsExactly(ps5);
        assertThatThrownBy(
                        () ->
                                new RatingStatistics.Available(
                                        null, 0, Collections.nCopies(10, 0), List.of(ps5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RatingStatistics.PlatformStatistics(
                                        "ps5", "PS5", BigDecimal.TEN, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RatingStatistics.PlatformStatistics(
                                        "ps5", "PS5", new BigDecimal("10.1"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
