package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.adapter.session.PendingIdentityProviderLogout;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Completes a logout command by sending the browser through the identity provider.
 *
 * <p>An authentication-navigation route under {@code /auth}, outside the product OpenAPI contract.
 * After {@code POST /api/v1/session} ended the application session, this redirect ends the same
 * browser's identity-provider session through standard OIDC RP-initiated logout; the provider then
 * returns to the realm's exact post-logout URI. An anonymous browser can ask Keycloak to show
 * its own logout confirmation without an ID token; a live BFF session is never ended by GET.
 */
@RestController
public class LogoutCompletionController {

    private final CookieClearingLogoutHandler sessionCookie;
    private final String confirmation;

    LogoutCompletionController(
            @Value("${server.servlet.session.cookie.name:vgp_session}") String sessionCookieName,
            ObjectProvider<ClientRegistrationRepository> registrations,
            @Value("${identity.oidc.end-session-uri}") String endSessionUri,
            @Value("${identity.oidc.post-logout-redirect-uri}") String postLogoutRedirectUri) {
        this.sessionCookie = new CookieClearingLogoutHandler(sessionCookieName);
        var repository = registrations.getIfAvailable();
        var registration = repository == null ? null : repository.findByRegistrationId("keycloak");
        this.confirmation =
                registration == null
                        ? null
                        : UriComponentsBuilder.fromUriString(endSessionUri)
                                .queryParam("client_id", registration.getClientId())
                                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                                .build()
                                .toUriString();
    }

    @GetMapping("/auth/logout")
    public ResponseEntity<Void> complete(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String target =
                authentication != null && authentication.getPrincipal() instanceof OidcUser
                        ? RatingBoundary.home()
                        : PendingIdentityProviderLogout.take(request)
                                .map(
                                        url -> {
                                            sessionCookie.logout(request, response, null);
                                            return url;
                                        })
                                .orElseGet(
                                        () -> {
                                            if (confirmation == null) {
                                                return RatingBoundary.home();
                                            }
                                            var session = request.getSession(false);
                                            if (session != null) session.invalidate();
                                            sessionCookie.logout(request, response, null);
                                            // Templates are server configuration, never request
                                            // redirect
                                            // parameters.
                                            String baseUrl =
                                                    UriComponentsBuilder.fromUriString(
                                                                    request.getRequestURL()
                                                                            .toString())
                                                            .replacePath(request.getContextPath())
                                                            .replaceQuery(null)
                                                            .build()
                                                            .toUriString();
                                            return confirmation.replace("{baseUrl}", baseUrl);
                                        });
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
