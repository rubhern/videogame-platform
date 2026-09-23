-- Add durable command metadata to the existing ratings-owned current-state table.
-- Defaults preserve existing aggregate-only rows and keep the migration additive.
ALTER TABLE ratings.rating
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN version_token uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT ck_rating_update_time CHECK (updated_at >= created_at);
