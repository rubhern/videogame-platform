package com.videogameplatform.identity.application;

import java.util.Optional;
import java.util.function.Function;

/** Supplies only a server-derived product identity to collaborating inbound adapters. */
public interface CurrentUser {
    Optional<String> currentUserId();

    /**
     * Runs a personal state change as the current user while its session cannot be revoked.
     *
     * <p>Every state change made on a user's behalf goes through this method. An administrative
     * revocation waits until running changes have returned, and a change that starts after its
     * session was revoked or ended is refused before it runs, so data deleted after a confirmed
     * revocation cannot be restored by a late request.
     *
     * @param change receives the server-derived user identifier
     * @throws CurrentUserUnavailableException when the request has no valid authenticated session
     */
    <T> T changeAsCurrentUser(Function<String, T> change);
}
