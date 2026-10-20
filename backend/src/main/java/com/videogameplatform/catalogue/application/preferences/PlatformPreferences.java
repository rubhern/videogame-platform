package com.videogameplatform.catalogue.application.preferences;

import java.util.List;

/**
 * A user's preferred platforms, ordered by name. {@code configured} is false until the user first
 * saves a choice; an empty saved choice is configured, which tells a skipped first-run offer apart
 * from one never made.
 */
public record PlatformPreferences(boolean configured, List<PlatformOption> platforms) {
    public PlatformPreferences {
        platforms = List.copyOf(platforms);
        if (!configured && !platforms.isEmpty()) {
            throw new IllegalArgumentException("Preferences exist only once a choice was saved");
        }
    }

    /** A user who has never saved a choice. */
    public static PlatformPreferences notConfigured() {
        return new PlatformPreferences(false, List.of());
    }
}
