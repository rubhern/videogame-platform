package com.videogameplatform.api.delivery.catalogue.preferences;

import com.videogameplatform.api.generated.PlatformPreferencesApi;
import com.videogameplatform.api.generated.model.PlatformPreferences;
import com.videogameplatform.api.generated.model.PlatformPreferencesWrite;
import com.videogameplatform.catalogue.application.preferences.GetPlatformPreferencesUseCase;
import com.videogameplatform.catalogue.application.preferences.ReplacePlatformPreferencesUseCase;
import com.videogameplatform.identity.application.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal HTTP adapter for UC-014. The owner is always the session's server-derived user; the
 * request names platforms only, never a user, and every response is {@code no-store}.
 */
@RestController
@RequestMapping("/api/v1")
public class PlatformPreferencesController implements PlatformPreferencesApi {
    private final CurrentUser currentUser;
    private final GetPlatformPreferencesUseCase reads;
    private final ReplacePlatformPreferencesUseCase writes;

    PlatformPreferencesController(
            CurrentUser currentUser,
            GetPlatformPreferencesUseCase reads,
            ReplacePlatformPreferencesUseCase writes) {
        this.currentUser = currentUser;
        this.reads = reads;
        this.writes = writes;
    }

    @Override
    public ResponseEntity<PlatformPreferences> getMyPlatformPreferences() {
        String userId =
                currentUser
                        .currentUserId()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Security filter admitted no user"));
        return noStore(PlatformPreferencesMapper.toResponse(reads.get(userId)));
    }

    @Override
    public ResponseEntity<PlatformPreferences> putMyPlatformPreferences(
            String csrfToken, PlatformPreferencesWrite command) {
        var stored =
                currentUser.changeAsCurrentUser(
                        userId -> writes.replace(userId, command.getPlatformIds()));
        return noStore(PlatformPreferencesMapper.toResponse(stored));
    }

    private static ResponseEntity<PlatformPreferences> noStore(PlatformPreferences body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
