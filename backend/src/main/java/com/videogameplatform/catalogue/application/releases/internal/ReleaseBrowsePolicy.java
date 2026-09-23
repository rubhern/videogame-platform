package com.videogameplatform.catalogue.application.releases.internal;

/** Product policy for the visible release windows and explicit TBA treatment. */
public record ReleaseBrowsePolicy(
        int releaseGroupLimit, UnknownUpcomingDatePolicy unknownUpcomingDatePolicy) {

    public ReleaseBrowsePolicy {
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
