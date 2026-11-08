package com.videogameplatform.catalogue.application.preferences;

import java.time.Instant;

/** Includes the authoritative save timestamp, including a deliberately saved empty choice. */
public interface ExportPlatformPreferences {
    Choice read(String userId);

    record Choice(PlatformPreferences preferences, Instant savedAt) {}
}
