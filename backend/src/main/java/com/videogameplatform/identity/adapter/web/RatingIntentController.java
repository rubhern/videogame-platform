package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.adapter.session.RatingReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.adapter.session.ResumedRatingIntent;
import com.videogameplatform.identity.domain.UserId;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BFF entry points for the rating authentication boundary.
 *
 * <p>These are authentication-navigation routes under {@code /auth}, not part of the product
 * OpenAPI contract. {@code start} is the only place a visitor begins authentication: it captures
 * the selected value in a server-side return context and hands the browser to the established
 * Keycloak OIDC flow. It never executes or persists a rating command. The recovered selection is
 * read back once from the game page and presented as pending, non-persisted state.
 */
@RestController
public class RatingIntentController {

    // The established authorization entry (authorizationRequestBaseUri + registrationId).
    private static final String LOGIN_ENTRY = "/auth/login/keycloak";

    private final RatingReturnContextStore store;
    private final Clock clock;

    RatingIntentController(RatingReturnContextStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Starts authentication from the rating boundary, preserving the selected value.
     *
     * <p>An anonymous visitor is redirected into the Keycloak OIDC flow after the return context is
     * stored. An already-authenticated visitor skips the identity provider and returns directly to
     * the game with the recovered selection. Invalid input never starts authentication.
     */
    @GetMapping("/auth/rating-intent/start")
    public ResponseEntity<Void> start(
            @RequestParam("gameId") String gameId,
            @RequestParam(name = "slug", required = false) String slug,
            @RequestParam(name = "value", required = false) String value) {
        if (!RatingBoundary.isValidGameId(gameId)) {
            return redirect(RatingBoundary.home());
        }
        Optional<Integer> selectedValue = RatingBoundary.validValue(value);
        if (selectedValue.isEmpty()) {
            return redirect(
                    RatingBoundary.gamePathWithOutcome(
                            gameId, slug, RatingBoundary.Outcome.INVALID));
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Optional<UserId> userId = ProductIdentity.resolve(authentication);
        if (userId.isPresent()) {
            store.saveResumedIntent(
                    new ResumedRatingIntent(
                            gameId, slug, selectedValue.get(), userId.get().value()));
            return redirect(
                    RatingBoundary.gamePathWithOutcome(
                            gameId, slug, RatingBoundary.Outcome.RESUMED));
        }

        store.saveReturnContext(
                new RatingReturnContext(gameId, slug, selectedValue.get(), clock.instant()));
        return redirect(LOGIN_ENTRY);
    }

    /**
     * Reads and consumes the recovered selection exactly once for the current authenticated user.
     *
     * <p>Single-use: the store removes it on this read, so a reload or replay cannot resume it
     * again. Absence, an anonymous session, or a selection bound to a different user all return
     * {@code 404} with no body.
     */
    @GetMapping("/auth/rating-intent")
    public ResponseEntity<PendingRatingIntent> pendingRatingIntent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Optional<UserId> userId = ProductIdentity.resolve(authentication);
        Optional<ResumedRatingIntent> resumed = store.takeResumedIntent();
        if (userId.isEmpty()
                || resumed.isEmpty()
                || !userId.get().value().equals(resumed.get().userId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        ResumedRatingIntent intent = resumed.get();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PendingRatingIntent(intent.gameId(), intent.slug(), intent.value()));
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /** The pending, non-persisted rating selection recovered after authentication. */
    public record PendingRatingIntent(String gameId, String slug, int value) {}
}
