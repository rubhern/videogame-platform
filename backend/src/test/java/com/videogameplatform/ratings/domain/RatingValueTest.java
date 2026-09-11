package com.videogameplatform.ratings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RatingValueTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void acceptsTheInclusiveDomainBoundary(int value) {
        assertThat(new RatingValue(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, 0, 11, Integer.MAX_VALUE})
    void rejectsValuesOutsideTheDomainBoundary(int value) {
        assertThatThrownBy(() -> new RatingValue(value))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
