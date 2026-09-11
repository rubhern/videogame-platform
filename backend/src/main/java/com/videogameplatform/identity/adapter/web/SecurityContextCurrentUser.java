package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.domain.UserId;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;

/** Translates the validated server-side OIDC security context into product identity. */
public final class SecurityContextCurrentUser implements CurrentUser {
    @Override
    public Optional<String> currentUserId() {
        return ProductIdentity.resolve(SecurityContextHolder.getContext().getAuthentication())
                .map(UserId::value);
    }
}
