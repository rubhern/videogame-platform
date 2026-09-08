package com.videogameplatform.ratings.domain;

import java.time.LocalDate;
import java.util.List;

/** Global eligibility from complete commercial release evidence, independent of freshness. */
public final class RatingEligibilityPolicy {
    public enum Reason {
        ELIGIBLE_RELEASE_FOUND,
        NO_COMMERCIAL_RELEASE,
        RELEASE_NOT_OCCURRED,
        RELEASE_CANCELLED,
        RELEASE_DATE_UNCERTAIN,
        RELEASE_REVIEW_REQUIRED
    }

    public record Evidence(
            boolean released,
            boolean cancelled,
            boolean reviewRequired,
            boolean verified,
            boolean exactDay,
            LocalDate periodEnd) {}

    public Reason evaluate(List<Evidence> releases, LocalDate evaluatedOn) {
        if (releases.isEmpty()) {
            return Reason.NO_COMMERCIAL_RELEASE;
        }
        var reasons = releases.stream().map(r -> evaluate(r, evaluatedOn)).toList();
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

    private Reason evaluate(Evidence release, LocalDate today) {
        if (release.cancelled()) {
            return Reason.RELEASE_CANCELLED;
        }
        if (release.reviewRequired()) {
            return Reason.RELEASE_REVIEW_REQUIRED;
        }
        if (release.periodEnd() == null && !release.verified()) {
            return Reason.RELEASE_DATE_UNCERTAIN;
        }
        if (!release.released()) {
            return Reason.RELEASE_NOT_OCCURRED;
        }
        if (release.periodEnd() == null) {
            return Reason.ELIGIBLE_RELEASE_FOUND;
        }
        boolean occurred =
                release.exactDay()
                        ? !today.isBefore(release.periodEnd())
                        : today.isAfter(release.periodEnd());
        return occurred ? Reason.ELIGIBLE_RELEASE_FOUND : Reason.RELEASE_NOT_OCCURRED;
    }
}
