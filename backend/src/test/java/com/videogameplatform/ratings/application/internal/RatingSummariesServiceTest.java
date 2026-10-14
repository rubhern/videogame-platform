package com.videogameplatform.ratings.application.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.ratings.application.RatingStatistics;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RatingSummariesServiceTest {
    @Test
    void boundsTheBatchAndDistinguishesUnavailableFromZeroAndOneRating() {
        var calls = new AtomicInteger();
        var expected =
                Map.of(
                        "one",
                        Optional.of(new RatingStatistics.Summary(BigDecimal.TEN, 1)),
                        "none",
                        Optional.of(new RatingStatistics.Summary(null, 0)),
                        "unknown",
                        Optional.<RatingStatistics.Summary>empty());
        var service =
                new RatingSummariesService(
                        ids -> {
                            calls.incrementAndGet();
                            return expected;
                        });
        assertThat(service.get(Set.of())).isEmpty();
        assertThat(service.get(expected.keySet())).isEqualTo(expected);
        assertThatThrownBy(
                        () ->
                                service.get(
                                        IntStream.range(0, 101)
                                                .mapToObj(Integer::toString)
                                                .collect(Collectors.toSet())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
