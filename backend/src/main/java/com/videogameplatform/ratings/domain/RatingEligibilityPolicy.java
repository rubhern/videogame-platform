package com.videogameplatform.ratings.domain;

import java.util.List;

/**
 * Global eligibility from complete commercial release evidence, independent of freshness.
 *
 * <p>Whether a known release date has already occurred is decided by the catalogue's effective
 * release status (derived from the release date and the trusted {@code Europe/Madrid} evaluation
 * date), so {@code released} here means the catalogue considers the release to have occurred, never
 * that a synchronization happened to run. A release without a temporal threshold (unknown date)
 * still requires explicit verified evidence that it occurred.
 */
public final class RatingEligibilityPolicy {
    public enum Reason {
        ELIGIBLE_RELEASE_FOUND,
        NO_COMMERCIAL_RELEASE,
        RELEASE_NOT_OCCURRED,
        RELEASE_CANCELLED,
        RELEASE_DATE_UNCERTAIN,
        RELEASE_REVIEW_REQUIRED
    }

    /**
     * @param released the catalogue's effective status is {@code RELEASED} (the release has
     *     occurred, whether derived from a known date or asserted for an unknown date)
     * @param cancelled the provider cancelled the release
     * @param delayed the provider delayed the release, so its stored date is not trustworthy
     * @param reviewRequired the normalized evidence still needs human review
     * @param verified the evidence is verified rather than provider-only
     * @param unknownDate the release has no temporal threshold to reason about
     */
    public record Evidence(
            boolean released,
            boolean cancelled,
            boolean delayed,
            boolean reviewRequired,
            boolean verified,
            boolean unknownDate) {}

    public Reason evaluate(List<Evidence> releases) {
        if (releases.isEmpty()) {
            return Reason.NO_COMMERCIAL_RELEASE;
        }
        var reasons = releases.stream().map(this::evaluate).toList();
        // Any valid release proves global eligibility. Otherwise report the most actionable
        // blocker.
        for (Reason reason :
                List.of(
                        Reason.ELIGIBLE_RELEASE_FOUND,
                        Reason.RELEASE_REVIEW_REQUIRED,
                        Reason.RELEASE_DATE_UNCERTAIN,
                        Reason.RELEASE_NOT_OCCURRED)) {
            if (reasons.contains(reason)) {
                return reason;
            }
        }
        return Reason.RELEASE_CANCELLED;
    }

    private Reason evaluate(Evidence release) {
        if (release.cancelled()) {
            return Reason.RELEASE_CANCELLED;
        }
        if (release.reviewRequired()) {
            return Reason.RELEASE_REVIEW_REQUIRED;
        }
        if (release.delayed()) {
            return Reason.RELEASE_NOT_OCCURRED;
        }
        if (release.unknownDate()) {
            return release.released() && release.verified()
                    ? Reason.ELIGIBLE_RELEASE_FOUND
                    : Reason.RELEASE_DATE_UNCERTAIN;
        }
        return release.released() ? Reason.ELIGIBLE_RELEASE_FOUND : Reason.RELEASE_NOT_OCCURRED;
    }
}
