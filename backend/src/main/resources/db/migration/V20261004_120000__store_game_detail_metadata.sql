-- Issue #233: developers, publishers, genres and game modes of a Game, acquired by catalogue
-- synchronization and served from PostgreSQL. Expand-only: new tables that older application
-- versions never read. The game summary keeps its existing game_snapshot columns.
--
-- Companies, genres and game modes are product entities with internal UUID identity, resolved
-- through typed provider external references exactly like platform and region (ADR-0020): a
-- provider ID is never product identity and names never merge entities. A Game's links are its
-- current provider-acquired state, replaced as a whole inside the Game transaction by a valid
-- provider answer; an invalid answer or a failed Game keeps the last valid links.

CREATE TABLE catalogue.company (
    company_id uuid PRIMARY KEY,
    display_name varchar(300) NOT NULL,
    CONSTRAINT ck_company_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE catalogue.company_external_reference (
    provider varchar(100) NOT NULL,
    provider_id varchar(200) NOT NULL,
    company_id uuid NOT NULL,
    PRIMARY KEY (provider, provider_id),
    CONSTRAINT uq_company_external_reference_company UNIQUE (provider, company_id),
    CONSTRAINT fk_company_external_reference_company
        FOREIGN KEY (company_id) REFERENCES catalogue.company (company_id),
    CONSTRAINT ck_company_external_reference_provider_not_blank CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_company_external_reference_provider_id_not_blank CHECK (btrim(provider_id) <> '')
);

-- One row per credited role: a company that both developed and published a Game has two rows.
CREATE TABLE catalogue.game_company (
    game_id uuid NOT NULL,
    company_role varchar(16) NOT NULL,
    company_id uuid NOT NULL,
    PRIMARY KEY (game_id, company_role, company_id),
    CONSTRAINT fk_game_company_game FOREIGN KEY (game_id) REFERENCES catalogue.game (game_id),
    CONSTRAINT fk_game_company_company
        FOREIGN KEY (company_id) REFERENCES catalogue.company (company_id),
    CONSTRAINT ck_game_company_role CHECK (company_role IN ('developer', 'publisher'))
);

CREATE TABLE catalogue.genre (
    genre_id uuid PRIMARY KEY,
    code varchar(100) NOT NULL,
    display_name varchar(200) NOT NULL,
    CONSTRAINT uq_genre_code UNIQUE (code),
    CONSTRAINT ck_genre_code CHECK (code ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_genre_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE catalogue.genre_external_reference (
    provider varchar(100) NOT NULL,
    provider_id varchar(200) NOT NULL,
    genre_id uuid NOT NULL,
    PRIMARY KEY (provider, provider_id),
    CONSTRAINT uq_genre_external_reference_genre UNIQUE (provider, genre_id),
    CONSTRAINT fk_genre_external_reference_genre
        FOREIGN KEY (genre_id) REFERENCES catalogue.genre (genre_id),
    CONSTRAINT ck_genre_external_reference_provider_not_blank CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_genre_external_reference_provider_id_not_blank CHECK (btrim(provider_id) <> '')
);

CREATE TABLE catalogue.game_genre (
    game_id uuid NOT NULL,
    genre_id uuid NOT NULL,
    PRIMARY KEY (game_id, genre_id),
    CONSTRAINT fk_game_genre_game FOREIGN KEY (game_id) REFERENCES catalogue.game (game_id),
    CONSTRAINT fk_game_genre_genre FOREIGN KEY (genre_id) REFERENCES catalogue.genre (genre_id)
);

CREATE TABLE catalogue.game_mode (
    game_mode_id uuid PRIMARY KEY,
    code varchar(100) NOT NULL,
    display_name varchar(200) NOT NULL,
    CONSTRAINT uq_game_mode_code UNIQUE (code),
    CONSTRAINT ck_game_mode_code CHECK (code ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_game_mode_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE catalogue.game_mode_external_reference (
    provider varchar(100) NOT NULL,
    provider_id varchar(200) NOT NULL,
    game_mode_id uuid NOT NULL,
    PRIMARY KEY (provider, provider_id),
    CONSTRAINT uq_game_mode_external_reference_game_mode UNIQUE (provider, game_mode_id),
    CONSTRAINT fk_game_mode_external_reference_game_mode
        FOREIGN KEY (game_mode_id) REFERENCES catalogue.game_mode (game_mode_id),
    CONSTRAINT ck_game_mode_external_reference_provider_not_blank CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_game_mode_external_reference_provider_id_not_blank
        CHECK (btrim(provider_id) <> '')
);

CREATE TABLE catalogue.game_game_mode (
    game_id uuid NOT NULL,
    game_mode_id uuid NOT NULL,
    PRIMARY KEY (game_id, game_mode_id),
    CONSTRAINT fk_game_game_mode_game FOREIGN KEY (game_id) REFERENCES catalogue.game (game_id),
    CONSTRAINT fk_game_game_mode_game_mode
        FOREIGN KEY (game_mode_id) REFERENCES catalogue.game_mode (game_mode_id)
);

COMMENT ON TABLE catalogue.game_company IS
    'Current developer and publisher credits of a Game, replaced as a whole by a valid provider answer.';
COMMENT ON TABLE catalogue.game_genre IS
    'Current genres of a Game, replaced as a whole by a valid provider answer.';
COMMENT ON TABLE catalogue.game_game_mode IS
    'Current game modes of a Game, replaced as a whole by a valid provider answer.';
