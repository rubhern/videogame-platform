package com.videogameplatform.catalogue.application.releases.internal;

/** Product policy bounding the releases grouped under one game in a release page. */
public record ReleaseBrowsePolicy(int releaseGroupLimit) {

    public ReleaseBrowsePolicy {
        if (releaseGroupLimit < 1) {
            throw new IllegalArgumentException(
                    "Release group must be bounded to at least 1 release per game");
        }
    }
}
