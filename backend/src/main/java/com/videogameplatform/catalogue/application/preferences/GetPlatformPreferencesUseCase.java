package com.videogameplatform.catalogue.application.preferences;

/** Reads one user's preferred platforms; the caller supplies the server-derived user identity. */
public interface GetPlatformPreferencesUseCase {
    PlatformPreferences get(String userId);
}
