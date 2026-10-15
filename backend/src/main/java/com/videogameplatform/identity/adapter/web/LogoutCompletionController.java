package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.adapter.session.PendingIdentityProviderLogout;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Completes a logout command by sending the browser through the identity provider.
 *
 * <p>An authentication-navigation route under {@code /auth}, outside the product OpenAPI contract.
 * After {@code POST /api/v1/session} ended the application session, this redirect ends the same
 * browser's identity-provider session through standard OIDC RP-initiated logout; the provider then
 * returns to the realm's exact post-logout URI. Without a pending logout it only returns home and
 * changes nothing.
 */
@RestController
public class LogoutCompletionController {

    private final CookieClearingLogoutHandler sessionCookie;

    LogoutCompletionController(
            @Value("${server.servlet.session.cookie.name:vgp_session}") String sessionCookieName) {
        this.sessionCookie = new CookieClearingLogoutHandler(sessionCookieName);
    }

    @GetMapping("/auth/logout")
    public ResponseEntity<Void> complete(HttpServletRequest request, HttpServletResponse response) {
        String target =
                PendingIdentityProviderLogout.take(request)
                        .map(
                                url -> {
                                    sessionCookie.logout(request, response, null);
                                    return url;
                                })
                        .orElseGet(RatingBoundary::home);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
