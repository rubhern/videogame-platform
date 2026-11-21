ALTER TABLE catalogue.game_library_entry
    ADD COLUMN priority bigint,
    ADD CONSTRAINT uq_game_library_priority UNIQUE (user_id, priority)
        DEFERRABLE INITIALLY DEFERRED;

COMMENT ON COLUMN catalogue.game_library_entry.priority IS
    'Private ordering weight. Higher values come first; null follows release order.';
