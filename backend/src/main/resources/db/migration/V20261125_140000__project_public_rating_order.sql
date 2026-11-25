ALTER TABLE ratings.game_listing
    ADD COLUMN first_release_on date,
    ADD COLUMN catalogue_visits double precision CHECK (catalogue_visits > 0 AND catalogue_visits <= 1),
    ADD COLUMN profile_order_projected boolean NOT NULL DEFAULT false;
