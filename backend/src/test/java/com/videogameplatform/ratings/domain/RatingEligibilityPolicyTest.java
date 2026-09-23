package com.videogameplatform.ratings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.ratings.domain.RatingEligibilityPolicy.Evidence;
import com.videogameplatform.ratings.domain.RatingEligibilityPolicy.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Eligibility consumes the catalogue's effective release status: a known date that has occurred is
 * already reported as released, so eligibility no longer depends on a synchronization transition.
 */
class RatingEligibilityPolicyTest {
    private final RatingEligibilityPolicy policy = new RatingEligibilityPolicy();

    @Test
    void knownDatedReleaseIsEligibleOnceTheCatalogueReportsItReleased() {
        assertThat(evaluate(occurredKnownDate())).isEqualTo(Reason.ELIGIBLE_RELEASE_FOUND);
        assertThat(evaluate(pendingKnownDate())).isEqualTo(Reason.RELEASE_NOT_OCCURRED);
    }

    @Test
    void unknownDateNeedsVerifiedExplicitEvidenceThatItOccurred() {
        // released + verified, but no temporal threshold: explicit evidence is enough.
        assertThat(evaluate(new Evidence(true, false, false, false, true, true)))
                .isEqualTo(Reason.ELIGIBLE_RELEASE_FOUND);
        // released but only provider-only evidence is not enough without a date.
        assertThat(evaluate(new Evidence(true, false, false, false, false, true)))
                .isEqualTo(Reason.RELEASE_DATE_UNCERTAIN);
        // announced unknown date is never eligible.
        assertThat(evaluate(new Evidence(false, false, false, false, true, true)))
                .isEqualTo(Reason.RELEASE_DATE_UNCERTAIN);
    }

    @Test
    void cancelledDelayedAndReviewNeverProveARelease() {
        assertThat(evaluate(new Evidence(false, true, false, false, true, false)))
                .isEqualTo(Reason.RELEASE_CANCELLED);
        assertThat(evaluate(new Evidence(false, false, true, false, true, false)))
                .isEqualTo(Reason.RELEASE_NOT_OCCURRED);
        assertThat(evaluate(new Evidence(true, false, false, true, true, false)))
                .isEqualTo(Reason.RELEASE_REVIEW_REQUIRED);
        assertThat(policy.evaluate(List.of())).isEqualTo(Reason.NO_COMMERCIAL_RELEASE);
    }

    @Test
    void oneValidReleaseWinsAndBlockerSelectionIsIndependentOfTupleOrder() {
        var released = occurredKnownDate();
        var review = new Evidence(true, false, false, true, true, false);
        var uncertain = new Evidence(true, false, false, false, false, true);
        assertThat(policy.evaluate(List.of(review, released)))
                .isEqualTo(Reason.ELIGIBLE_RELEASE_FOUND);
        assertThat(policy.evaluate(List.of(review, uncertain)))
                .isEqualTo(Reason.RELEASE_REVIEW_REQUIRED);
        assertThat(policy.evaluate(List.of(uncertain, review)))
                .isEqualTo(Reason.RELEASE_REVIEW_REQUIRED);
    }

    private static Evidence occurredKnownDate() {
        return new Evidence(true, false, false, false, false, false);
    }

    private static Evidence pendingKnownDate() {
        return new Evidence(false, false, false, false, false, false);
    }

    private Reason evaluate(Evidence evidence) {
        return policy.evaluate(List.of(evidence));
    }
}
