package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.SessionApi;
import com.videogameplatform.api.generated.model.AnonymousSession;
import com.videogameplatform.api.generated.model.AuthenticatedSession;
import com.videogameplatform.api.generated.model.SessionState;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inbound BFF adapter exposing only the reviewed application-session representation. */
@RestController
@RequestMapping("/api/v1")
public class SessionController implements SessionApi {

    private final HttpServletRequest request;

    SessionController(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public ResponseEntity<SessionState> getSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SessionState body =
                isAuthenticatedOidcSession(authentication)
                        ? authenticatedSession()
                        : new AnonymousSession(false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @Override
    public ResponseEntity<Void> logoutSession(String csrfToken) {
        // The Spring Security logout filter owns this operation. This contract method is the
        // defensive MVC fallback if filter ordering is changed accidentally.
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private AuthenticatedSession authenticatedSession() {
        Object csrfAttribute = request.getAttribute(CsrfToken.class.getName());
        if (!(csrfAttribute instanceof CsrfToken csrfToken)) {
            throw new IllegalStateException("Authenticated session has no CSRF material");
        }
        return new AuthenticatedSession(true, csrfToken.getToken());
    }

    private static boolean isAuthenticatedOidcSession(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken oauth2
                && oauth2.isAuthenticated()
                && oauth2.getPrincipal() instanceof OidcUser;
    }
}
