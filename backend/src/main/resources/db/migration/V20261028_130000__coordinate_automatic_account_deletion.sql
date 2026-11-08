-- Expand the already-applied admission schema without rewriting its history.
ALTER TABLE identity.account_deletion
    ADD COLUMN ledger_digest varchar(64),
    ADD COLUMN proof_digest varchar(64);

-- Earlier completions have no persisted verification receipt. Keep their admission
-- and access fence, and require the protected executor to verify the same request.
UPDATE identity.account_deletion
SET state = 'pending', completed_at = NULL, claim = NULL,
    next_attempt_at = CURRENT_TIMESTAMP
WHERE state = 'complete';

ALTER TABLE identity.account_deletion
    ADD CONSTRAINT account_deletion_ledger_digest_format
        CHECK (ledger_digest IS NULL OR ledger_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT account_deletion_proof_digest_format
        CHECK (proof_digest IS NULL OR proof_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT account_deletion_completion_evidence
        CHECK (state <> 'complete' OR (ledger_digest IS NOT NULL AND proof_digest IS NOT NULL));

-- Readiness only, never erasure evidence. One executor owns protected administration.
CREATE TABLE identity.deletion_executor (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    seen_at timestamptz NOT NULL
);
