-- Issue #450: operator-editable product settings (UC-024). Expand-only: new tables that older
-- application versions never read. Only operator overrides are stored; a setting without a row
-- takes the default the application registers, so this migration seeds no values and a restart or
-- deployment never overwrites an operator's choice.

-- One revision counter that every accepted change advances; an update names the revision it was
-- prepared from and is refused when another change came first.
CREATE TABLE catalogue.product_setting_revision (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision bigint NOT NULL CHECK (revision >= 0),
    updated_at timestamptz
);

INSERT INTO catalogue.product_setting_revision (singleton, revision, updated_at)
VALUES (true, 0, NULL);

-- The operator's current value of one setting. The key is the application registry's stable key;
-- a key a later application no longer registers is ignored, never misread.
CREATE TABLE catalogue.product_setting (
    setting_key varchar(80) PRIMARY KEY
        CHECK (setting_key ~ '^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$'),
    setting_value numeric(16, 4) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    changed_at timestamptz NOT NULL,
    changed_by uuid NOT NULL
);

-- The audit trail: who changed which setting when, from which effective value to which. One row per
-- setting and revision; restoring the default deletes the override and records the change here.
CREATE TABLE catalogue.product_setting_change (
    revision bigint NOT NULL CHECK (revision > 0),
    setting_key varchar(80) NOT NULL
        CHECK (setting_key ~ '^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$'),
    previous_value numeric(16, 4) NOT NULL,
    new_value numeric(16, 4) NOT NULL,
    restored_default boolean NOT NULL,
    changed_at timestamptz NOT NULL,
    changed_by uuid NOT NULL,
    PRIMARY KEY (revision, setting_key)
);

COMMENT ON TABLE catalogue.product_setting_revision IS
    'Single revision counter of the operator product settings; guards concurrent updates.';
COMMENT ON TABLE catalogue.product_setting IS
    'Operator overrides of registered product settings; a missing row means the registered default.';
COMMENT ON TABLE catalogue.product_setting_change IS
    'Audit trail of product setting changes with the operator''s derived product identity.';
COMMENT ON COLUMN catalogue.product_setting_change.changed_by IS
    'Derived UserId of the authenticated catalogue operator; opaque, never an email or subject.';
