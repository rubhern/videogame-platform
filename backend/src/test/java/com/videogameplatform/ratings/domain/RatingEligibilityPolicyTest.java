package com.videogameplatform.ratings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.ratings.domain.RatingEligibilityPolicy.Evidence;
import com.videogameplatform.ratings.domain.RatingEligibilityPolicy.Reason;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RatingEligibilityPolicyTest {
    private final RatingEligibilityPolicy policy = new RatingEligibilityPolicy();

    @ParameterizedTest
    @CsvSource({
        "2026-08-13,true,2026-08-12,RELEASE_NOT_OCCURRED",
        "2026-08-13,true,2026-08-13,ELIGIBLE_RELEASE_FOUND",
        "2026-08-13,true,2026-08-14,ELIGIBLE_RELEASE_FOUND",
        "2028-02-29,false,2028-02-29,RELEASE_NOT_OCCURRED",
        "2028-02-29,false,2028-03-01,ELIGIBLE_RELEASE_FOUND",
        "2026-06-30,false,2026-06-30,RELEASE_NOT_OCCURRED",
        "2026-06-30,false,2026-07-01,ELIGIBLE_RELEASE_FOUND",
        "2026-12-31,false,2026-12-31,RELEASE_NOT_OCCURRED",
        "2026-12-31,false,2027-01-01,ELIGIBLE_RELEASE_FOUND"
    })
    void respectsExactDayAndConservativePartialDateBoundaries(
            String end, boolean day, String today, Reason expected) {
        assertThat(
                        policy.evaluate(
                                List.of(
                                        new Evidence(
                                                true,
                                                false,
                                                false,
                                                false,
                                                day,
                                                LocalDate.parse(end))),
                                LocalDate.parse(today)))
                .isEqualTo(expected);
    }

    @Test
    void uncertainReleasedEvidenceNeedsVerificationAndNeverBypassesReview() {
        assertThat(evaluate(new Evidence(true, false, false, false, false, null)))
                .isEqualTo(Reason.RELEASE_DATE_UNCERTAIN);
        assertThat(evaluate(new Evidence(true, false, false, true, false, null)))
                .isEqualTo(Reason.ELIGIBLE_RELEASE_FOUND);
        assertThat(evaluate(new Evidence(true, false, true, true, false, null)))
                .isEqualTo(Reason.RELEASE_REVIEW_REQUIRED);
        assertThat(evaluate(new Evidence(false, false, false, true, false, null)))
                .isEqualTo(Reason.RELEASE_NOT_OCCURRED);
    }

    @Test
    void neverTreatsCancelledOrNonReleasedEvidenceAsARelease() {
        assertThat(evaluate(new Evidence(false, true, true, true, true, LocalDate.MIN)))
                .isEqualTo(Reason.RELEASE_CANCELLED);
        assertThat(evaluate(new Evidence(false, false, false, true, true, LocalDate.MIN)))
                .isEqualTo(Reason.RELEASE_NOT_OCCURRED);
        assertThat(policy.evaluate(List.of(), LocalDate.of(2026, 8, 13)))
                .isEqualTo(Reason.NO_COMMERCIAL_RELEASE);
    }

    @Test
    void oneValidReleaseWinsAndBlockerSelectionIsIndependentOfTupleOrder() {
        var released = new Evidence(true, false, false, false, true, LocalDate.of(2020, 1, 1));
        var review = new Evidence(true, false, true, true, true, LocalDate.of(2020, 1, 1));
        var uncertain = new Evidence(true, false, false, false, false, null);
        var today = LocalDate.of(2026, 8, 13);
        assertThat(policy.evaluate(List.of(review, released), today))
                .isEqualTo(Reason.ELIGIBLE_RELEASE_FOUND);
        assertThat(policy.evaluate(List.of(review, uncertain), today))
                .isEqualTo(Reason.RELEASE_REVIEW_REQUIRED);
        assertThat(policy.evaluate(List.of(uncertain, review), today))
                .isEqualTo(Reason.RELEASE_REVIEW_REQUIRED);
    }

    private Reason evaluate(Evidence evidence) {
        return policy.evaluate(List.of(evidence), LocalDate.of(2026, 8, 13));
    }
}
