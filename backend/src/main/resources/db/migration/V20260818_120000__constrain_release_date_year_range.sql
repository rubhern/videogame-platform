ALTER TABLE catalogue.release_snapshot
    ADD CONSTRAINT ck_release_snapshot_exact_date_year
    CHECK (
        exact_date IS NULL
        OR extract(year FROM exact_date) BETWEEN 1 AND 9999
    );
