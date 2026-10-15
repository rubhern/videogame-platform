package com.videogameplatform.identity.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends an OIDC session that outlived its absolute lifetime or that revocation could not reach.
 *
 * <p>The lifetime runs from the ID token's {@code auth_time} (its {@code iat} when absent), so
 * requests never extend it and no BFF session outlives the identity provider's own maximum session
 * counted from the same authentication. A session absent from the registry could not be revoked,
 * so it is ended as well. The request then continues anonymously, exactly like a session Spring
 * Security's concurrent-session filter found revoked.
 */
final class SessionLifetimeFilter extends OncePerRequestFilter {

    private final SessionRegistry sessions;
    private final Clock clock;
    private final Duration absoluteTimeout;
    private final LogoutHandler endSession;

    SessionLifetimeFilter(
            SessionRegistry sessions,
            Clock clock,
            Duration absoluteTimeout,
            LogoutHandler endSession) {
        if (absoluteTimeout.isNegative() || absoluteTimeout.isZero()) {
            throw new IllegalArgumentException("absoluteTimeout must be positive");
        }
        this.sessions = sessions;
        this.clock = clock;
        this.absoluteTimeout = absoluteTimeout;
        this.endSession = endSession;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser user
                && !isWithinLifetime(request.getSession(false), user)) {
            endSession.logout(request, response, authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isWithinLifetime(HttpSession session, OidcUser user) {
        Instant authenticatedAt =
                user.getAuthenticatedAt() != null ? user.getAuthenticatedAt() : user.getIssuedAt();
        return session != null
                && sessions.getSessionInformation(session.getId()) != null
                && authenticatedAt != null
                && clock.instant().isBefore(authenticatedAt.plus(absoluteTimeout));
    }
}
