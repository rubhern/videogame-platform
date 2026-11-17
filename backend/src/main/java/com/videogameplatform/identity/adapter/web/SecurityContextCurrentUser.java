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
    private final java.util.function.Predicate<String> blocked;

    public SecurityContextCurrentUser(SessionRevocation sessions) {
        this(sessions, ignored -> false);
    }

    public SecurityContextCurrentUser(
            SessionRevocation sessions, java.util.function.Predicate<String> blocked) {
        this.sessions = sessions;
        this.blocked = blocked;
    }

    @Override
    public Optional<String> currentUserId() {
        return ProductIdentity.resolve(SecurityContextHolder.getContext().getAuthentication())
                .map(UserId::value)
                .filter(id -> !blocked.test(id));
    }

    @Override
    public <T> T changeAsCurrentUser(Function<String, T> change) {
        String userId = currentUserId().orElseThrow(CurrentUserUnavailableException::new);
        String sessionId = currentSessionId().orElseThrow(CurrentUserUnavailableException::new);
        return sessions.whileActive(
                sessionId,
                () -> {
                    if (blocked.test(userId)) throw new CurrentUserUnavailableException();
                    return change.apply(userId);
                });
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
