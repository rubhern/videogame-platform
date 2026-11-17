-- Issue #456: a deferred work leaves no local trace, so "this period was scanned" cannot say whether
-- a work the import policy now admits was seen and deferred. Each interval records which policy
-- revision scanned it. Every existing interval predates the wider policy and takes revision 1, and
-- the default keeps a rolled-back application from claiming a newer revision.
ALTER TABLE catalogue.synchronization_coverage
    ADD COLUMN import_policy_revision integer NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_synchronization_coverage_import_policy_revision
        CHECK (import_policy_revision >= 1);

COMMENT ON COLUMN catalogue.synchronization_coverage.import_policy_revision IS
    'Import policy revision of the scan; a historical skip only trusts the current revision.';
