-- Clear unpublished switches and withdraw alias-only publications from the initial schema.
UPDATE identity.player_alias
SET published = false, share_ratings = false, share_wishlist = false,
    revision = gen_random_uuid()
WHERE published <> (share_ratings OR share_wishlist);

ALTER TABLE identity.player_alias
    ADD CONSTRAINT ck_player_alias_publication_sections
    CHECK (published = (share_ratings OR share_wishlist));
