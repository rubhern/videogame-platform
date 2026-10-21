package com.videogameplatform.ratings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RatingScoreTest {
    @Test
    void aDirectValueIsTheContributionWhateverPlatformsWerePlayed() {
        var score = new RatingScore(new RatingValue(9), List.of(played("ps5"), played("pc")));

        assertThat(score.mode()).isEqualTo(RatingScore.Mode.DIRECT);
        assertThat(score.contribution()).isEqualByComparingTo("9");
        assertThat(score.scoredPlatforms()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"9;8,8.5", "8;8;9,8.3", "7;8,7.5", "10,10.0", "1;2;2,1.7", "9;9;8;8;8;8,8.3"})
    void scoredPlatformsContributeTheirMeanRoundedHalfUpOnlyForExposure(
            String values, String expected) {
        var platforms =
                IntStream.range(0, values.split(";").length)
                        .mapToObj(
                                index ->
                                        new PlatformScore(
                                                "platform-" + index,
                                                new RatingValue(
                                                        Integer.parseInt(
                                                                values.split(";")[index]))))
                        .toList();
        var score = new RatingScore(null, platforms);

        assertThat(score.mode()).isEqualTo(RatingScore.Mode.PLATFORMS);
        assertThat(score.contribution()).isEqualByComparingTo(expected);
    }

    @Test
    void unscoredPlatformsNeverTakePartInTheMean() {
        var score =
                new RatingScore(
                        null,
                        List.of(
                                new PlatformScore("ps5", new RatingValue(9)),
                                played("switch"),
                                new PlatformScore("pc", new RatingValue(6))));

        assertThat(score.scoredPlatforms()).isEqualTo(2);
        assertThat(score.platformScoreSum()).isEqualTo(15);
        assertThat(score.contribution()).isEqualByComparingTo("7.5");
    }

    @Test
    void aRatingHasExactlyOneScoreSource() {
        assertThatThrownBy(() -> new RatingScore(null, List.of(played("ps5"))))
                .isInstanceOfSatisfying(
                        InvalidRatingScoreException.class,
                        exception ->
                                assertThat(exception.violation())
                                        .isEqualTo(
                                                InvalidRatingScoreException.Violation
                                                        .SCORE_SOURCE));
        assertThatThrownBy(() -> new RatingScore(null, List.of()))
                .isInstanceOf(InvalidRatingScoreException.class);
        assertThatThrownBy(
                        () ->
                                new RatingScore(
                                        new RatingValue(8),
                                        List.of(new PlatformScore("ps5", new RatingValue(9)))))
                .isInstanceOfSatisfying(
                        InvalidRatingScoreException.class,
                        exception ->
                                assertThat(exception.violation())
                                        .isEqualTo(
                                                InvalidRatingScoreException.Violation
                                                        .SCORE_SOURCE));
    }

    @Test
    void aPlatformAppearsOnceAndAtMostFiftyAppear() {
        assertThatThrownBy(
                        () ->
                                new RatingScore(
                                        new RatingValue(8), List.of(played("ps5"), played("ps5"))))
                .isInstanceOfSatisfying(
                        InvalidRatingScoreException.class,
                        exception ->
                                assertThat(exception.violation())
                                        .isEqualTo(
                                                InvalidRatingScoreException.Violation.PLATFORMS));
        var fifty = IntStream.range(0, 50).mapToObj(index -> played("p" + index)).toList();
        assertThat(new RatingScore(new RatingValue(8), fifty).platforms()).hasSize(50);
        var fiftyOne = IntStream.range(0, 51).mapToObj(index -> played("p" + index)).toList();
        assertThatThrownBy(() -> new RatingScore(new RatingValue(8), fiftyOne))
                .isInstanceOf(InvalidRatingScoreException.class);
    }

    private static PlatformScore played(String platformId) {
        return new PlatformScore(platformId, null);
    }
}
