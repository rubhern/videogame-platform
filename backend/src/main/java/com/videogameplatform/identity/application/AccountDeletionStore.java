package com.videogameplatform.identity.application;

import java.time.Instant;
import java.util.Optional;

/** Durable admission fence; production off-host evidence remains owned by the erasure engine. */
public interface AccountDeletionStore {
    default boolean requiresIndependentEvidence() {
        return true;
    }

    boolean blocked(String userId);

    boolean admit(String userId, String subject, String requestId, String receiptHash, Instant now);

    Optional<String> status(String receiptHash, Instant now);

    Optional<Work> claim(Instant now);

    void finish(Work work, boolean verified, Instant now);

    void executorSeen(Instant now);

    boolean executorReady(Instant now);

    boolean acknowledge(
            Work work, boolean verified, String ledgerDigest, String proofDigest, Instant now);

    record Work(String userId, String subject, String requestId, String claim) {}
}
