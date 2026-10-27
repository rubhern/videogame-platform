package com.videogameplatform.catalogue.application.preferences.internal;

import com.videogameplatform.catalogue.application.preferences.GetPlatformPreferencesUseCase;
import com.videogameplatform.catalogue.application.preferences.PlatformPreferenceInvalidException;
import com.videogameplatform.catalogue.application.preferences.PlatformPreferences;
import com.videogameplatform.catalogue.application.preferences.ReplacePlatformPreferencesUseCase;
import com.videogameplatform.catalogue.application.preferences.port.PlatformPreferenceStore;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-independent preferred-platform use cases. Every operation is scoped to the user the
 * caller derived from the authenticated session; no identifier from the request selects a user.
 */
// Modulith observability uses a class proxy for this bean, which exposes two use cases.
public class PlatformPreferencesService
        implements GetPlatformPreferencesUseCase, ReplacePlatformPreferencesUseCase {
    private final PlatformPreferenceStore store;
    private final Clock clock;

    public PlatformPreferencesService(PlatformPreferenceStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public PlatformPreferences get(String userId) {
        return store.find(requireUser(userId));
    }

    @Override
    public PlatformPreferences replace(String userId, List<String> platformIds) {
        String owner = requireUser(userId);
        Set<String> requested = new LinkedHashSet<>();
        for (String platformId : Objects.requireNonNull(platformIds, "platformIds")) {
            String trimmed = platformId == null ? "" : platformId.trim();
            if (trimmed.isEmpty()) {
                throw new PlatformPreferenceInvalidException();
            }
            requested.add(trimmed);
        }
        if (requested.size() > MAX_PLATFORMS) {
            throw new PlatformPreferenceInvalidException();
        }
        return store.replace(owner, Set.copyOf(requested), clock.instant())
                .orElseThrow(PlatformPreferenceInvalidException::new);
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("A preferred-platform operation needs its user");
        }
        return userId;
    }
}
