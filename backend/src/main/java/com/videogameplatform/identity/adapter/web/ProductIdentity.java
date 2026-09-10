package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.domain.UserId;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Resolves the stable product {@link UserId} from an authenticated OIDC principal.
 *
 * <p>Identity is taken only from the validated {@code issuer} and {@code subject}. Email, username,
 * roles, and any client-supplied identifier are deliberately ignored.
 */
public final class ProductIdentity {

    private ProductIdentity() {}

    public static Optional<UserId> resolve(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.isAuthenticated()
                && token.getPrincipal() instanceof OidcUser user
                && user.getIssuer() != null
                && user.getSubject() != null) {
            return Optional.of(
                    UserId.fromIssuerAndSubject(user.getIssuer().toString(), user.getSubject()));
        }
        return Optional.empty();
    }
}
