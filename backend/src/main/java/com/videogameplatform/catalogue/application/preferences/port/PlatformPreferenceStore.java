package com.videogameplatform.catalogue.application.preferences.port;

import com.videogameplatform.catalogue.application.preferences.PlatformPreferences;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Outbound port for the preferred platforms each product user owns. */
public interface PlatformPreferenceStore {

    /** The user's stored choice, or a not-configured choice when the user never saved one. */
    PlatformPreferences find(String userId);

    /**
     * Replaces the user's whole set in one transaction and records the choice at {@code savedAt}.
     *
     * @return the stored choice, or empty when any identifier names no platform of the local
     *     taxonomy, in which case nothing changed
     */
    Optional<PlatformPreferences> replace(String userId, Set<String> platformIds, Instant savedAt);
}
