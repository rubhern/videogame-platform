CREATE TABLE identity.player_alias (
    alias varchar(24) COLLATE "C" PRIMARY KEY,
    user_id uuid UNIQUE,
    chosen_at timestamptz,
    revision uuid,
    ever_published boolean NOT NULL,
    published boolean NOT NULL,
    share_ratings boolean NOT NULL,
    share_wishlist boolean NOT NULL,
    CHECK (char_length(alias) BETWEEN 3 AND 24 AND alias ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CHECK (NOT published OR ever_published),
    CHECK (
        (user_id IS NOT NULL AND chosen_at IS NOT NULL AND revision IS NOT NULL)
        OR
        (user_id IS NULL AND chosen_at IS NULL AND revision IS NULL
            AND ever_published AND NOT published AND NOT share_ratings AND NOT share_wishlist)
    )
);

COMMENT ON TABLE identity.player_alias IS
    'One lowercase alias per account. A retired public alias reserves only its public spelling.';

-- Used by rename, protected operator reset, account erasure and restoration replay.
CREATE FUNCTION identity.retire_player_alias(target_user uuid, target_alias text)
RETURNS boolean
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    current_alias text;
    was_public boolean;
BEGIN
    IF (target_user IS NULL) = (target_alias IS NULL) THEN
        RAISE EXCEPTION 'Select one owner or one alias';
    END IF;

    IF target_user IS NOT NULL THEN
        SELECT a.alias, a.ever_published INTO current_alias, was_public
        FROM identity.player_alias a WHERE a.user_id = target_user FOR UPDATE;
    ELSE
        SELECT a.alias, a.ever_published INTO current_alias, was_public
        FROM identity.player_alias a WHERE a.alias = target_alias AND a.user_id IS NOT NULL
        FOR UPDATE;
    END IF;
    IF NOT FOUND THEN RETURN false; END IF;

    IF was_public THEN
        UPDATE identity.player_alias
        SET user_id = NULL, chosen_at = NULL, revision = NULL,
            published = false, share_ratings = false, share_wishlist = false
        WHERE alias = current_alias;
    ELSE
        DELETE FROM identity.player_alias WHERE alias = current_alias;
    END IF;
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION identity.retire_player_alias(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity.retire_player_alias(uuid, text) TO videogame_app;
