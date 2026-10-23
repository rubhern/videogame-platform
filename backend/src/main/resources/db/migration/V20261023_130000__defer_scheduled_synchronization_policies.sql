-- Fixed periodic-policy slots survive a restart while a historical month owns UC-009.
-- Repeated due events coalesce into the latest requested window; no occurrence backlog.
CREATE TABLE catalogue.deferred_synchronization_policy (
    policy varchar(16) PRIMARY KEY CHECK (policy IN ('near_term','upcoming')),
    request_id uuid NOT NULL,
    window_from date NOT NULL,
    window_to date NOT NULL,
    due_at timestamptz NOT NULL,
    requested_at timestamptz NOT NULL,
    CHECK (window_from <= window_to)
);
