CREATE SCHEMA identity;
REVOKE ALL ON SCHEMA identity FROM PUBLIC;
GRANT USAGE ON SCHEMA identity TO videogame_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO videogame_app;
-- Admission and access fencing only. Never substitutes for the independent off-host ledger.
CREATE TABLE identity.account_deletion (
    user_id uuid PRIMARY KEY,
    subject uuid NOT NULL UNIQUE,
    request_id uuid NOT NULL UNIQUE,
    receipt_hash varchar(64) NOT NULL UNIQUE CHECK (receipt_hash ~ '^[0-9a-f]{64}$'),
    receipt_expires_at timestamptz NOT NULL,
    requested_at timestamptz NOT NULL,
    state varchar(8) NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'complete')),
    next_attempt_at timestamptz NOT NULL,
    claim uuid,
    completed_at timestamptz,
    CHECK ((state = 'complete') = (completed_at IS NOT NULL)),
    CHECK (receipt_expires_at > requested_at)
);
CREATE INDEX account_deletion_pending ON identity.account_deletion (next_attempt_at, user_id)
    WHERE state = 'pending';
COMMENT ON TABLE identity.account_deletion IS
    'Principal-derived deletion admission/access fence; independent ledger and archive are mandatory evidence.';
