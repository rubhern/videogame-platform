package com.videogameplatform.identity.adapter.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.RedirectStrategy;

/**
 * Carries the identity provider's end-session URL from the CSRF-protected logout command to the
 * top-level navigation that completes it.
 *
 * <p>Spring Security builds the standard OIDC RP-initiated logout URL, including the ended
 * session's ID token as {@code id_token_hint}, after the authenticated session is invalidated.
 * Instead of returning that URL to JavaScript, this strategy keeps it in a fresh, unauthenticated
 * session and answers the logout command with {@code 204}. The browser then navigates to {@code
 * /auth/logout}, which consumes the URL once and redirects there, so the token travels only in a
 * redirect to the identity provider.
 */
public final class PendingIdentityProviderLogout implements RedirectStrategy {

    private static final String ATTRIBUTE = PendingIdentityProviderLogout.class.getName();

    @Override
    public void sendRedirect(HttpServletRequest request, HttpServletResponse response, String url) {
        request.getSession(true).setAttribute(ATTRIBUTE, url);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    /**
     * Consumes the pending end-session URL and ends the session that carried it.
     *
     * <p>A session that carries none, including an authenticated one, is left untouched, so a
     * navigation another site induces cannot end it.
     */
    public static Optional<String> take(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        try {
            if (!(session.getAttribute(ATTRIBUTE) instanceof String url)) {
                return Optional.empty();
            }
            session.invalidate();
            return Optional.of(url);
        } catch (IllegalStateException alreadyConsumed) {
            return Optional.empty();
        }
    }
}
