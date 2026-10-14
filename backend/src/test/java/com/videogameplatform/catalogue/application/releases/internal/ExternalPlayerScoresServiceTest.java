package com.videogameplatform.catalogue.application.releases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExternalPlayerScoresServiceTest {
    @Test
    void readsAtMostOneSelectedPageAndSkipsEmptySelections() {
        var calls = new AtomicInteger();
        var service =
                new ExternalPlayerScoresService(
                        ids -> {
                            calls.incrementAndGet();
                            assertThat(ids).hasSize(100);
                            return Map.of();
                        });
        assertThat(service.get(Set.of())).isEmpty();
        assertThat(
                        service.get(
                                IntStream.range(0, 100)
                                        .mapToObj(Integer::toString)
                                        .collect(Collectors.toSet())))
                .isEmpty();
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
