package com.videogameplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalScoreTest {

    @Test
    void keepsTheDecimalMeanOnItsDeclaredScaleWithoutTruncatingIt() {
        var score = new ExternalScore(new BigDecimal("83.67289380364453"), 604);

        assertThat(score.value()).isEqualByComparingTo("83.672894");
        assertThat(score.value().scale()).isEqualTo(ExternalScore.FRACTION_DIGITS);
        assertThat(score.count()).isEqualTo(604);
        assertThat(ExternalScore.SCALE_MAX).isEqualByComparingTo("100");
    }

    @Test
    void aSingleRatingIsAUsableScore() {
        assertThat(new ExternalScore(new BigDecimal("70"), 1).count()).isEqualTo(1);
    }

    @Test
    void equalValuesAreEqualWhateverScaleTheProviderWroteThemIn() {
        assertThat(new ExternalScore(new BigDecimal("87.4"), 1434))
                .isEqualTo(new ExternalScore(new BigDecimal("87.400000000"), 1434));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "100", "100.0000000"})
    void acceptsTheClosedDeclaredScale(String value) {
        assertThat(new ExternalScore(new BigDecimal(value), 3).value()).isEqualByComparingTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.000001", "100.0000001", "101", "-5"})
    void rejectsAValueOutsideTheDeclaredScale(String value) {
        assertThatThrownBy(() -> new ExternalScore(new BigDecimal(value), 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aScoreWithoutPositiveRatingsIsNotAScore(long count) {
        assertThatThrownBy(() -> new ExternalScore(new BigDecimal("80"), count))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aScoreRequiresItsValue() {
        assertThatThrownBy(() -> new ExternalScore(null, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
