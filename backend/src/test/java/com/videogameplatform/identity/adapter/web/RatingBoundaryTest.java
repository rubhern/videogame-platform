package com.videogameplatform.identity.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RatingBoundaryTest {

    private static final String GAME_ID = "30000000-0000-4000-8000-000000000005";
    private static final String SLUG = "resident-evil-requiem";

    @Test
    void acceptsOnlyValuesInTheApprovedRange() {
        assertThat(RatingBoundary.validValue("1")).contains(1);
        assertThat(RatingBoundary.validValue("10")).contains(10);
        assertThat(RatingBoundary.validValue("0")).isEmpty();
        assertThat(RatingBoundary.validValue("11")).isEmpty();
        assertThat(RatingBoundary.validValue("8.5")).isEmpty();
        assertThat(RatingBoundary.validValue("eight")).isEmpty();
        assertThat(RatingBoundary.validValue(null)).isEmpty();
    }

    @Test
    void acceptsOnlyGameIdentifiersMatchingTheRouteCharacterSet() {
        assertThat(RatingBoundary.isValidGameId(GAME_ID)).isTrue();
        assertThat(RatingBoundary.isValidGameId("../etc/passwd")).isFalse();
        assertThat(RatingBoundary.isValidGameId("https://attacker.example")).isFalse();
        assertThat(RatingBoundary.isValidGameId("Game With Spaces")).isFalse();
        assertThat(RatingBoundary.isValidGameId(null)).isFalse();
    }

    @Test
    void buildsOnlyLocalGamePathsAndNeverAnOpenRedirect() {
        assertThat(RatingBoundary.gamePath(GAME_ID, SLUG))
                .isEqualTo("/games/" + GAME_ID + "/" + SLUG);
        assertThat(RatingBoundary.gamePath(GAME_ID, null)).isEqualTo("/games/" + GAME_ID);
        assertThat(RatingBoundary.gamePath(GAME_ID, "//attacker.example"))
                .isEqualTo("/games/" + GAME_ID);
        assertThat(
                        RatingBoundary.gamePathWithOutcome(
                                GAME_ID, SLUG, RatingBoundary.Outcome.RESUMED))
                .isEqualTo("/games/" + GAME_ID + "/" + SLUG + "?rating-intent=resumed");
    }
}
