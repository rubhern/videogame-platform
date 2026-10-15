package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import com.videogameplatform.identity.domain.UserId;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Translates the validated server-side OIDC security context into product identity. */
public final class SecurityContextCurrentUser implements CurrentUser {

    private final SessionRevocation sessions;

    public SecurityContextCurrentUser(SessionRevocation sessions) {
        this.sessions = sessions;
    }

    @Override
    public Optional<String> currentUserId() {
        return ProductIdentity.resolve(SecurityContextHolder.getContext().getAuthentication())
                .map(UserId::value);
    }

    @Override
    public <T> T changeAsCurrentUser(Function<String, T> change) {
        String userId = currentUserId().orElseThrow(CurrentUserUnavailableException::new);
        String sessionId = currentSessionId().orElseThrow(CurrentUserUnavailableException::new);
        return sessions.whileActive(sessionId, () -> change.apply(userId));
    }

    private static Optional<String> currentSessionId() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes request) {
            return Optional.ofNullable(request.getRequest().getSession(false))
                    .map(HttpSession::getId);
        }
        return Optional.empty();
    }
}
