package com.videogameplatform.api.delivery.catalogue.preferences;

import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.PlatformPreferences;
import com.videogameplatform.catalogue.application.preferences.PlatformOption;

/** Explicit mapping from the preferred-platform application models to their transport shapes. */
final class PlatformPreferencesMapper {
    private PlatformPreferencesMapper() {}

    static Platform toResponse(PlatformOption platform) {
        return new Platform(platform.platformId(), platform.name());
    }

    static PlatformPreferences toResponse(
            com.videogameplatform.catalogue.application.preferences.PlatformPreferences
                    preferences) {
        return new PlatformPreferences(
                preferences.configured(),
                preferences.platforms().stream()
                        .map(PlatformPreferencesMapper::toResponse)
                        .toList());
    }
}
