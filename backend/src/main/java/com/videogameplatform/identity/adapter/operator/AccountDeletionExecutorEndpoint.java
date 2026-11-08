package com.videogameplatform.identity.adapter.operator;

import com.videogameplatform.identity.adapter.web.SessionRevocation;
import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.domain.UserId;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/** Separate private management port only. No browser-facing script or credential transport. */
@Endpoint(id = "accountdeletionexecutor")
public final class AccountDeletionExecutorEndpoint {
    private final AccountDeletionStore store;
    private final SessionRevocation sessions;
    private final DeletionExecutorCredential credential;
    private final Supplier<String> authorization;
    private final Clock clock;
    private final String issuer;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public AccountDeletionExecutorEndpoint(
            AccountDeletionStore store,
            SessionRevocation sessions,
            DeletionExecutorCredential credential,
            Supplier<String> authorization,
            Clock clock,
            String issuer,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this.store = store;
        this.sessions = sessions;
        this.credential = credential;
        this.authorization = authorization;
        this.clock = clock;
        this.issuer = issuer;
        this.metrics = metrics;
    }

    @WriteOperation
    public WebEndpointResponse<?> exchange(
            String action,
            @Nullable String userId,
            @Nullable String requestId,
            @Nullable String claim,
            @Nullable Boolean verified,
            @Nullable String ledgerSha256,
            @Nullable String proofSha256) {
        if (!credential.accepts(authorization.get())) return response(401, "EXECUTOR_UNAUTHORIZED");
        if ("heartbeat".equals(action)) {
            store.executorSeen(clock.instant());
            return new WebEndpointResponse<>(
                    Map.of(
                            "ready",
                            true,
                            "policy",
                            store.requiresIndependentEvidence() ? "independent" : "private-dev"));
        }
        if ("claim".equals(action)) {
            store.executorSeen(clock.instant());
            var next = store.claim(clock.instant());
            if (next.isEmpty()) return new WebEndpointResponse<>(Map.of("idle", true));
            var work = next.get();
            if (!sessions.revoke(new UserId(work.userId())).writesSettled()) {
                store.finish(work, false, clock.instant());
                return response(503, "WRITES_NOT_SETTLED");
            }
            return new WebEndpointResponse<>(
                    Map.of(
                            "version",
                            1,
                            "issuer",
                            issuer,
                            "subject",
                            work.subject(),
                            "userId",
                            work.userId(),
                            "requestId",
                            work.requestId(),
                            "claim",
                            work.claim()));
        }
        if (!"finish".equals(action)
                || !uuid(userId)
                || !uuid(requestId)
                || !uuid(claim)
                || verified == null) return response(400, "EXECUTOR_REQUEST_INVALID");
        if (verified
                && store.requiresIndependentEvidence()
                && (!digest(ledgerSha256) || !digest(proofSha256)))
            return response(400, "EVIDENCE_REQUIRED");
        if (verified
                && !store.requiresIndependentEvidence()
                && (ledgerSha256 != null || proofSha256 != null))
            return response(400, "UNEXPECTED_EVIDENCE");
        boolean accepted =
                store.acknowledge(
                        new AccountDeletionStore.Work(userId, "", requestId, claim),
                        verified,
                        ledgerSha256,
                        proofSha256,
                        clock.instant());
        if (accepted)
            metrics.counter("account.deletion.attempts", "outcome", verified ? "verified" : "retry")
                    .increment();
        return accepted
                ? new WebEndpointResponse<>(Map.of("accepted", true))
                : response(409, "STALE_EXECUTOR_CLAIM");
    }

    private static WebEndpointResponse<?> response(int status, String code) {
        return new WebEndpointResponse<>(Map.of("code", code), status);
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
