CREATE TABLE catalogue.game_external_reference (
    game_id uuid NOT NULL,
    provider varchar(100) NOT NULL,
    provider_entity_type varchar(100) NOT NULL,
    provider_id varchar(200) NOT NULL,
    provider_url varchar(500),
    PRIMARY KEY (game_id, provider, provider_entity_type),
    CONSTRAINT fk_game_external_reference_game
        FOREIGN KEY (game_id)
        REFERENCES catalogue.game (game_id),
    CONSTRAINT uq_game_external_reference_provider_identity
        UNIQUE (provider, provider_entity_type, provider_id),
    CONSTRAINT ck_game_external_reference_provider_not_blank
        CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_game_external_reference_entity_type
        CHECK (provider_entity_type IN ('game', 'platform', 'release')),
    CONSTRAINT ck_game_external_reference_provider_id_not_blank
        CHECK (btrim(provider_id) <> ''),
    CONSTRAINT ck_game_external_reference_provider_url
        CHECK (provider_url IS NULL OR provider_url ~ '^https://')
);
