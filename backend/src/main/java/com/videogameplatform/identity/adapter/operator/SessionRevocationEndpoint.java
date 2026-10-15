package com.videogameplatform.identity.adapter.operator;

import com.videogameplatform.identity.adapter.web.SessionRevocation;
import com.videogameplatform.identity.domain.UserId;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/**
 * Private management command that signs one product user out of every BFF session.
 *
 * <p>Part of the owner-run account disable/delete procedure (ADR-0027); deliberately absent from
 * the product HTTP contract and served only by the separate management server. It accepts only a
 * derived {@link UserId}, answers only a count, and neither logs nor labels a user identifier.
 * Repeating it reports zero once the account has no active session, which is how the procedure
 * verifies the outcome.
 */
@Endpoint(id = "sessionrevocation")
public final class SessionRevocationEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRevocationEndpoint.class);
    private static final String INVALID_USER_ID = "INVALID_USER_ID";
    private static final String NOT_SETTLED = "SESSION_REVOCATION_NOT_SETTLED";
    // UserId.fromIssuerAndSubject derives name-based (version 3) UUIDs in canonical lowercase
    // form; anything else cannot match a session, and accepting it would let a mistyped
    // identifier report a misleading zero.
    private static final int DERIVED_UUID_VERSION = 3;
    private static final int IETF_VARIANT = 2;

    private final SessionRevocation revocation;

    public SessionRevocationEndpoint(SessionRevocation revocation) {
        this.revocation = revocation;
    }

    @WriteOperation
    public Object revoke(@Nullable String userId) {
        if (!isDerivedUserId(userId)) {
            LOGGER.atInfo()
                    .addKeyValue("identity.revocation.code", INVALID_USER_ID)
                    .log("BFF session revocation rejected: code={}", INVALID_USER_ID);
            return new WebEndpointResponse<>(Map.of("code", INVALID_USER_ID), 400);
        }
        SessionRevocation.Result result = revocation.revoke(new UserId(userId));
        LOGGER.atInfo()
                .addKeyValue("identity.revocation.sessions", result.revokedSessions())
                .addKeyValue("identity.revocation.settled", result.writesSettled())
                .log(
                        "BFF sessions revoked: sessions={}, settled={}",
                        result.revokedSessions(),
                        result.writesSettled());
        if (!result.writesSettled()) {
            return new WebEndpointResponse<>(
                    Map.of("code", NOT_SETTLED, "revokedSessions", result.revokedSessions()), 503);
        }
        return Map.of("revokedSessions", result.revokedSessions());
    }

    private static boolean isDerivedUserId(@Nullable String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.version() == DERIVED_UUID_VERSION
                    && uuid.variant() == IETF_VARIANT
                    && uuid.toString().equals(value);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
