package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.adapter.session.AuthenticationReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** General account entry through the established BFF, never a credential or token endpoint. */
@RestController
public class AuthenticationEntryController {
    private final RatingReturnContextStore store;
    private final Clock clock;

    AuthenticationEntryController(RatingReturnContextStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @GetMapping({"/auth/start", "/login"})
    public ResponseEntity<Void> start(
            @RequestParam(required = false) String returnTo,
            @RequestParam(required = false) String intent) {
        String target = AuthenticationReturnTarget.safePath(returnTo);
        if (ProductIdentity.resolve(SecurityContextHolder.getContext().getAuthentication())
                .isPresent()) {
            return redirect(target);
        }
        store.saveAuthenticationContext(new AuthenticationReturnContext(target, clock.instant()));
        return redirect(
                "/auth/login/keycloak" + ("register".equals(intent) ? "?intent=register" : ""));
    }

    private static ResponseEntity<Void> redirect(String target) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
