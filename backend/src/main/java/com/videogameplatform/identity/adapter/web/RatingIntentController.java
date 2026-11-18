package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.adapter.session.RatingReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContext.PlatformScore;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.adapter.session.ResumedRatingIntent;
import com.videogameplatform.identity.domain.UserId;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BFF entry points for the rating authentication boundary.
 *
 * <p>These are authentication-navigation routes under {@code /auth}, not part of the product
 * OpenAPI contract. {@code start} begins authentication for a rating selection: it captures
 * the selected value in a server-side return context and hands the browser to the established
 * Keycloak OIDC flow. It never executes or persists a rating command. The recovered selection is
 * read back once, from the game page or from the allowlisted list the rating started on, and
 * presented as pending, non-persisted state.
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
    @GetMapping(path = "/auth/rating-intent/start", params = "!csrf")
    public ResponseEntity<Void> start(
            @RequestParam("gameId") String gameId,
            @RequestParam(name = "slug", required = false) String slug,
            @RequestParam(name = "value", required = false) String value,
            @RequestParam(name = "returnTo", required = false) String returnTo,
            Authentication authentication) {
        Optional<UserId> userId = ProductIdentity.resolve(authentication);
        if (!RatingBoundary.isValidGameId(gameId)) {
            return redirect(RatingBoundary.home());
        }
        Optional<Integer> selectedValue = RatingBoundary.validValue(value);
        if (selectedValue.isEmpty()) {
            return redirect(
                    RatingBoundary.gamePathWithOutcome(
                            gameId, slug, RatingBoundary.Outcome.INVALID));
        }

        var selection =
                new RatingReturnContext(
                        gameId,
                        RatingBoundary.validSlug(slug).orElse(null),
                        selectedValue.get(),
                        List.of(),
                        clock.instant(),
                        RatingBoundary.validListPath(returnTo).orElse(null));
        stageSelection(selection, userId);
        return redirect(
                userId.isPresent()
                        ? RatingBoundary.returnPathWithOutcome(
                                selection, RatingBoundary.Outcome.RESUMED)
                        : LOGIN_ENTRY);
    }

    /** Issues the existing session CSRF proof before an anonymous form submission. */
    @GetMapping(
            path = "/auth/rating-intent/start",
            params = "csrf",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> startCsrf(CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(csrf.getToken());
    }

    /**
     * Collects platform scores in a CSRF-protected form body so the selection stays out of URLs.
     * The browser then follows the same local destination as the general rating entry.
     */
    @PostMapping(
            path = "/auth/rating-intent/start",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PlatformRatingStart> startPlatforms(
            @RequestParam("gameId") String gameId,
            @RequestParam(name = "slug", required = false) String slug,
            @RequestParam(name = "returnTo", required = false) String returnTo,
            @RequestParam MultiValueMap<String, String> form,
            Authentication authentication) {
        Optional<UserId> userId = ProductIdentity.resolve(authentication);
        Optional<List<PlatformScore>> platforms =
                RatingBoundary.validPlatforms(form.get("platform"));
        if (!RatingBoundary.isValidGameId(gameId) || platforms.isEmpty()) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        stageSelection(
                new RatingReturnContext(
                        gameId,
                        RatingBoundary.validSlug(slug).orElse(null),
                        null,
                        platforms.get(),
                        clock.instant(),
                        RatingBoundary.validListPath(returnTo).orElse(null)),
                userId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PlatformRatingStart(userId.isPresent()));
    }

    private void stageSelection(RatingReturnContext selection, Optional<UserId> userId) {
        if (userId.isPresent()) {
            store.saveResumedIntent(new ResumedRatingIntent(selection, userId.get().value()));
        } else {
            store.saveReturnContext(selection);
        }
    }

    public record PlatformRatingStart(boolean alreadyAuthenticated) {}

    /**
     * Reads and consumes the recovered selection exactly once for the current authenticated user.
     *
     * <p>Single-use: the store removes it on this read, so a reload or replay cannot resume it
     * again. Absence, an anonymous session, or a selection bound to a different user all return
     * {@code 404} with no body.
     */
    @GetMapping("/auth/rating-intent")
    public ResponseEntity<PendingRatingIntent> pendingRatingIntent(Authentication authentication) {
        Optional<UserId> userId = ProductIdentity.resolve(authentication);
        Optional<ResumedRatingIntent> resumed = store.takeResumedIntent();
        if (userId.isEmpty()
                || resumed.isEmpty()
                || !userId.get().value().equals(resumed.get().userId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        RatingReturnContext intent = resumed.get().selection();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new PendingRatingIntent(
                                intent.gameId(),
                                intent.slug(),
                                intent.value(),
                                intent.platforms()));
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /** The pending, non-persisted rating selection recovered after authentication. */
    public record PendingRatingIntent(
            String gameId, String slug, Integer value, List<PlatformScore> platforms) {}
}
