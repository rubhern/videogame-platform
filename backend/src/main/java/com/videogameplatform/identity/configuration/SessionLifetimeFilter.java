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
 * <p>Only a boolean true in the validated ID token selects remembered bounds. All other values
 * preserve the ordinary authentication-time bound. No request, userinfo or cookie can select it.
 */
final class SessionLifetimeFilter extends OncePerRequestFilter {

    private static final String CREATED_AT = SessionLifetimeFilter.class.getName() + ".createdAt";
    private final SessionRegistry sessions;
    private final Clock clock;
    private final Duration absoluteTimeout;
    private final Duration rememberedMaximum;
    private final LogoutHandler endSession;

    SessionLifetimeFilter(
            SessionRegistry sessions,
            Clock clock,
            Duration absoluteTimeout,
            Duration rememberedMaximum,
            LogoutHandler endSession) {
        if (absoluteTimeout.isNegative()
                || absoluteTimeout.isZero()
                || rememberedMaximum.isNegative()
                || rememberedMaximum.isZero()) {
            throw new IllegalArgumentException("Session timeouts must be positive");
        }
        this.sessions = sessions;
        this.clock = clock;
        this.absoluteTimeout = absoluteTimeout;
        this.rememberedMaximum = rememberedMaximum;
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

    static void created(HttpSession session, Instant now) {
        session.setAttribute(CREATED_AT, now);
    }

    private boolean isWithinLifetime(HttpSession session, OidcUser user) {
        return session != null
                && sessions.getSessionInformation(session.getId()) != null
                && withinLifetime(
                        session, user, clock.instant(), absoluteTimeout, rememberedMaximum);
    }

    static boolean withinLifetime(
            HttpSession session,
            OidcUser user,
            Instant now,
            Duration absoluteTimeout,
            Duration rememberedMaximum) {
        var idToken = user.getIdToken();
        Instant authenticatedAt =
                idToken.getAuthenticatedAt() != null
                        ? idToken.getAuthenticatedAt()
                        : idToken.getIssuedAt();
        if (Boolean.TRUE.equals(idToken.getClaims().get("gameometro_remember_me"))) {
            return session != null
                    && session.getAttribute(CREATED_AT) instanceof Instant createdAt
                    && idToken.getAuthenticatedAt() != null
                    && now.isBefore(createdAt.plus(absoluteTimeout))
                    && now.isBefore(idToken.getAuthenticatedAt().plus(rememberedMaximum));
        }
        return authenticatedAt != null && now.isBefore(authenticatedAt.plus(absoluteTimeout));
    }
}
