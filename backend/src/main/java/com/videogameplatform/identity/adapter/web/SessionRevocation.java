package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import com.videogameplatform.identity.domain.UserId;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

/**
 * Ends every BFF session of one product identity and fences personal state changes against it.
 *
 * <p>Authenticated sessions live only in this application's memory, and Spring Security's session
 * registry records each one when OIDC sign-in succeeds. Revocation marks every registered session
 * whose principal derives the requested {@link UserId} as expired, on every device; Spring
 * Security's concurrent-session filter then ends it on its next request.
 *
 * <p>A request already past that filter could still write. Personal state changes therefore run
 * under a shared lock and recheck their session inside it, while revocation holds the lock
 * exclusively: it first waits for running changes to return, and any change that starts later
 * finds its session expired and is refused. When {@link Result#writesSettled()} is true, no change
 * by a revoked session is running or can start, so deleting its data cannot be undone by a late
 * request. Personal writes from every user pause only while revocation waits, bounded by {@code
 * settleTimeout}; reads never take the lock.
 *
 * <p>The registry, the lock and the sessions share one process, so a restart discards all three
 * together and stays the fail-closed fallback (ADR-0027).
 */
public final class SessionRevocation {

    private final SessionRegistry sessions;
    private final Duration settleTimeout;
    private final ReentrantReadWriteLock changes = new ReentrantReadWriteLock();

    public SessionRevocation(SessionRegistry sessions, Duration settleTimeout) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        if (settleTimeout.isNegative() || settleTimeout.isZero()) {
            throw new IllegalArgumentException("settleTimeout must be positive");
        }
        this.settleTimeout = settleTimeout;
    }

    /**
     * Expires every active session of {@code userId} and reports how many it expired.
     *
     * <p>Idempotent: an expired session is no longer active, so a repeated call reports zero. If
     * running changes do not return within the settle timeout the sessions are still expired, but
     * the result is unsettled and only an application restart guarantees no late write.
     */
    public Result revoke(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        boolean settled = acquireExclusively();
        try {
            int revoked = 0;
            for (Object principal : sessions.getAllPrincipals()) {
                if (ProductIdentity.resolvePrincipal(principal)
                        .filter(userId::equals)
                        .isPresent()) {
                    for (SessionInformation session : sessions.getAllSessions(principal, false)) {
                        session.expireNow();
                        revoked++;
                    }
                }
            }
            return new Result(revoked, settled);
        } finally {
            if (settled) {
                changes.writeLock().unlock();
            }
        }
    }

    /**
     * Runs {@code change} only while the registered session is still active.
     *
     * @throws CurrentUserUnavailableException when the session is unknown, ended or revoked
     */
    public <T> T whileActive(String sessionId, Supplier<T> change) {
        changes.readLock().lock();
        try {
            SessionInformation session = sessions.getSessionInformation(sessionId);
            if (session == null || session.isExpired()) {
                throw new CurrentUserUnavailableException();
            }
            return change.get();
        } finally {
            changes.readLock().unlock();
        }
    }

    private boolean acquireExclusively() {
        try {
            return changes.writeLock().tryLock(settleTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Outcome of one revocation.
     *
     * @param revokedSessions active sessions this call expired
     * @param writesSettled whether no personal state change can still run for them
     */
    public record Result(int revokedSessions, boolean writesSettled) {}
}
