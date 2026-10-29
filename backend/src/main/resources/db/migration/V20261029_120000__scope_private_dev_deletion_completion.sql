-- Private-dev has no external privacy evidence requirement (owner decision #272).
-- Production rows retain the independent-evidence invariant and existing digests.
ALTER TABLE identity.account_deletion
    ADD COLUMN completion_policy varchar(16) NOT NULL DEFAULT 'independent'
        CHECK (completion_policy IN ('independent', 'private-dev')),
    DROP CONSTRAINT account_deletion_completion_evidence;
ALTER TABLE identity.account_deletion
    ADD CONSTRAINT account_deletion_completion_evidence CHECK (
        (completion_policy = 'independent' AND
            (state <> 'complete' OR (ledger_digest IS NOT NULL AND proof_digest IS NOT NULL)))
        OR (completion_policy = 'private-dev' AND ledger_digest IS NULL AND proof_digest IS NULL)
    );
ALTER TABLE identity.deletion_executor
    ADD COLUMN completion_policy varchar(16) NOT NULL DEFAULT 'independent'
        CHECK (completion_policy IN ('independent', 'private-dev'));
CREATE INDEX account_deletion_pending_policy
    ON identity.account_deletion (completion_policy, next_attempt_at, user_id)
    WHERE state = 'pending';
DROP INDEX identity.account_deletion_pending;
