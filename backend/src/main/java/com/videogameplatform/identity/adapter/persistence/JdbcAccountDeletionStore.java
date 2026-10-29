package com.videogameplatform.identity.adapter.persistence;

import com.videogameplatform.identity.application.AccountDeletionStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** Unique principal admission, indexed status/claim and restart-safe access fence. */
public final class JdbcAccountDeletionStore implements AccountDeletionStore {
    private final NamedParameterJdbcOperations jdbc;
    private final String policy;

    public JdbcAccountDeletionStore(NamedParameterJdbcOperations jdbc) {
        this(jdbc, "independent");
    }

    public JdbcAccountDeletionStore(NamedParameterJdbcOperations jdbc, String policy) {
        if (!java.util.Set.of("independent", "private-dev").contains(policy))
            throw new IllegalArgumentException("Unknown deletion completion policy");
        this.jdbc = jdbc;
        this.policy = policy;
    }

    @Override
    public boolean requiresIndependentEvidence() {
        return "independent".equals(policy);
    }

    @Override
    public boolean blocked(String userId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM identity.account_deletion WHERE user_id = :user)",
                        Map.of("user", UUID.fromString(userId)),
                        Boolean.class));
    }

    @Override
    public boolean admit(
            String userId, String subject, String requestId, String receiptHash, Instant now) {
        return jdbc.update(
                        """
            INSERT INTO identity.account_deletion
                (user_id, subject, request_id, receipt_hash, requested_at, receipt_expires_at, next_attempt_at, completion_policy)
            VALUES (:user, :subject, :request, :receipt, :now, :now + interval '24 hours', :now, :policy)
            ON CONFLICT (user_id) DO NOTHING
            """,
                        Map.of(
                                "policy",
                                policy,
                                "user",
                                UUID.fromString(userId),
                                "subject",
                                UUID.fromString(subject),
                                "request",
                                UUID.fromString(requestId),
                                "receipt",
                                receiptHash,
                                "now",
                                at(now)))
                == 1;
    }

    @Override
    public Optional<String> status(String receiptHash, Instant now) {
        return jdbc
                .query(
                        "SELECT state FROM identity.account_deletion WHERE receipt_hash = :receipt AND receipt_expires_at > :now",
                        Map.of("receipt", receiptHash, "now", at(now)),
                        (rs, row) -> rs.getString(1))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Work> claim(Instant now) {
        return jdbc
                .query(
                        """
            UPDATE identity.account_deletion SET claim = :claim, next_attempt_at = :now + interval '15 minutes'
            WHERE user_id = (SELECT user_id FROM identity.account_deletion
                WHERE state = 'pending' AND completion_policy = :policy AND next_attempt_at <= :now
                ORDER BY next_attempt_at, user_id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING user_id, subject, request_id, claim
            """,
                        Map.of("now", at(now), "claim", UUID.randomUUID(), "policy", policy),
                        (rs, row) ->
                                new Work(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4)))
                .stream()
                .findFirst();
    }

    @Override
    public void finish(Work work, boolean verified, Instant now) {
        if (verified)
            throw new IllegalArgumentException("Completion requires protected executor evidence");
        jdbc.update(
                """
            UPDATE identity.account_deletion SET state = :state, completed_at = :completed,
                next_attempt_at = :now + interval '5 minutes', claim = NULL
            WHERE user_id = :user AND claim = :claim AND state = 'pending'
            """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("state", verified ? "complete" : "pending")
                        .addValue(
                                "completed",
                                verified ? at(now) : null,
                                java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("now", at(now))
                        .addValue("user", UUID.fromString(work.userId()))
                        .addValue("claim", UUID.fromString(work.claim())));
    }

    @Override
    public void executorSeen(Instant now) {
        jdbc.update(
                "INSERT INTO identity.deletion_executor(singleton, seen_at, completion_policy) VALUES (true,:now,:policy) ON CONFLICT(singleton) DO UPDATE SET seen_at=EXCLUDED.seen_at, completion_policy=EXCLUDED.completion_policy",
                Map.of("now", at(now), "policy", policy));
    }

    @Override
    public boolean executorReady(Instant now) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM identity.deletion_executor WHERE completion_policy = :policy AND seen_at > :now - interval '60 seconds')",
                        Map.of("now", at(now), "policy", policy),
                        Boolean.class));
    }

    @Override
    public boolean acknowledge(
            Work work, boolean verified, String ledgerDigest, String proofDigest, Instant now) {
        if (verified
                && (requiresIndependentEvidence()
                        ? ledgerDigest == null || proofDigest == null
                        : ledgerDigest != null || proofDigest != null))
            throw new IllegalArgumentException(
                    "Completion receipt does not match this runtime policy");
        return jdbc.update(
                        """
            UPDATE identity.account_deletion SET state=:state, completed_at=:completed,
                ledger_digest=:ledger, proof_digest=:proof, next_attempt_at=:now + interval '5 minutes', claim=NULL
            WHERE user_id=:user AND request_id=:request AND claim=:claim AND state='pending' AND next_attempt_at > :now AND completion_policy=:policy
            """,
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                                .addValue("state", verified ? "complete" : "pending")
                                .addValue("policy", policy)
                                .addValue(
                                        "completed",
                                        verified ? at(now) : null,
                                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                                .addValue("ledger", verified ? ledgerDigest : null)
                                .addValue("proof", verified ? proofDigest : null)
                                .addValue("user", UUID.fromString(work.userId()))
                                .addValue("request", UUID.fromString(work.requestId()))
                                .addValue("claim", UUID.fromString(work.claim()))
                                .addValue("now", at(now)))
                == 1;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
