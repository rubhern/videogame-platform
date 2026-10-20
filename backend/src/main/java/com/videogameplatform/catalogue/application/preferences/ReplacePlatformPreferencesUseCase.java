package com.videogameplatform.catalogue.application.preferences;

import java.util.List;

/** Replaces one user's preferred platforms as a whole set and records that a choice was made. */
public interface ReplacePlatformPreferencesUseCase {

    /** The most platforms one user may prefer. */
    int MAX_PLATFORMS = 50;

    /**
     * Replaces the set atomically. An empty list clears it, which is also how a first-run choice is
     * skipped. Values are trimmed and de-duplicated.
     *
     * @throws PlatformPreferenceInvalidException when a value names no platform of the local
     *     taxonomy, or the set exceeds {@link #MAX_PLATFORMS}; nothing changes
     */
    PlatformPreferences replace(String userId, List<String> platformIds);
}
