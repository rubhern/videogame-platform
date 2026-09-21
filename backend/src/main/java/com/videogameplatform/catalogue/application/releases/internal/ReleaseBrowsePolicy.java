package com.videogameplatform.catalogue.application.releases.internal;

/** Product policy for the visible release windows and explicit TBA treatment. */
public record ReleaseBrowsePolicy(
        int recentWindowMonths,
        int upcomingWindowMonths,
        int releaseGroupLimit,
        UnknownUpcomingDatePolicy unknownUpcomingDatePolicy) {

    public ReleaseBrowsePolicy {
        if (recentWindowMonths < 1 || recentWindowMonths > 60) {
            throw new IllegalArgumentException(
                    "Recent release window must be between 1 and 60 months");
        }
        if (upcomingWindowMonths < 1 || upcomingWindowMonths > 60) {
            throw new IllegalArgumentException(
                    "Upcoming release window must be between 1 and 60 months");
        }
        if (releaseGroupLimit < 1) {
            throw new IllegalArgumentException(
                    "Release group must be bounded to at least 1 release per game");
        }
        if (unknownUpcomingDatePolicy == null) {
            throw new IllegalArgumentException("Unknown upcoming date policy is required");
        }
    }

    public boolean includesUnknownUpcomingDates() {
        return unknownUpcomingDatePolicy == UnknownUpcomingDatePolicy.INCLUDE_AS_TBA;
    }

    public enum UnknownUpcomingDatePolicy {
        INCLUDE_AS_TBA,
        EXCLUDE
    }
}
