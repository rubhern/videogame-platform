-- #591: one exclusive personal state per library entry. Existing entries are wishes.
-- Defaults keep an application instance from the previous release able to save wishes
-- during an upgrade handover.
ALTER TABLE catalogue.game_library_entry
    ADD COLUMN state text NOT NULL DEFAULT 'wished',
    ADD COLUMN state_changed_at timestamptz;

UPDATE catalogue.game_library_entry SET state_changed_at = wished_at;

ALTER TABLE catalogue.game_library_entry
    ALTER COLUMN state_changed_at SET NOT NULL,
    ALTER COLUMN state_changed_at SET DEFAULT now(),
    ALTER COLUMN wished_at DROP NOT NULL,
    ALTER COLUMN position_key DROP NOT NULL,
    ADD CONSTRAINT ck_game_library_state
        CHECK (state IN ('wished', 'backlog', 'playing', 'completed', 'abandoned')),
    ADD CONSTRAINT ck_game_library_wish_fields
        CHECK ((state = 'wished') = (wished_at IS NOT NULL)
               AND (state = 'wished') = (position_key IS NOT NULL)
               AND (state = 'wished' OR priority IS NULL));

CREATE INDEX ix_game_library_recent
    ON catalogue.game_library_entry (user_id, state_changed_at DESC, game_id);
CREATE INDEX ix_game_library_state_recent
    ON catalogue.game_library_entry (user_id, state, state_changed_at DESC, game_id);

COMMENT ON TABLE catalogue.game_library_entry IS
    'Personal: one entry per product UserId and catalogue Game in exactly one state.';
COMMENT ON COLUMN catalogue.game_library_entry.state IS
    'Private game-level state: wished, backlog, playing, completed or abandoned.';
COMMENT ON COLUMN catalogue.game_library_entry.state_changed_at IS
    'Time the entry last entered its current state; repeating the same state keeps it.';
COMMENT ON COLUMN catalogue.game_library_entry.wished_at IS
    'Time the game last became wished. Present only while the state is wished.';
COMMENT ON COLUMN catalogue.game_library_entry.position_key IS
    'Private wishlist sort key, present only while wished. A move allocates a key between existing neighbors without rewriting their positions.';
