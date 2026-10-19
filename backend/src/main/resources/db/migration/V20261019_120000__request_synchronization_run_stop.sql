-- An operator's request to stop one run (#342). The worker reads it at its safe points and
-- finishes the run itself; the request never changes run_status, ownership or coverage. It
-- stays on the finished run as evidence that a stop was asked for, whatever the result.
ALTER TABLE catalogue.synchronization_run ADD COLUMN stop_requested_at timestamptz;
COMMENT ON COLUMN catalogue.synchronization_run.stop_requested_at IS
    'When an operator first requested this run''s cooperative stop; NULL when nobody did.';
