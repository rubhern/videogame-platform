-- #235: source remains provider-owned; Spanish is derived product content.
-- Expand-only: no inference or full-catalogue summary rewrite occurs in this migration.
CREATE FUNCTION catalogue.spanish_source_fingerprint(source text) RETURNS varchar(64)
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT encode(sha256(convert_to('en:es:' || btrim(regexp_replace(source, E'[ \t\r\n]+', ' ', 'g')), 'UTF8')), 'hex');
$$;
CREATE TABLE catalogue.content_translation (
    fingerprint varchar(64) PRIMARY KEY CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    source_text text NOT NULL CHECK (length(source_text) BETWEEN 1 AND 20000),
    source_language varchar(2) NOT NULL DEFAULT 'en' CHECK (source_language = 'en'),
    target_language varchar(2) NOT NULL DEFAULT 'es' CHECK (target_language = 'es'),
    translated_text text CHECK (translated_text IS NULL OR (btrim(translated_text) <> '' AND length(translated_text) <= 20000)),
    translation_origin varchar(32) NOT NULL DEFAULT 'machine' CHECK (translation_origin = 'machine'),
    runtime_revision varchar(200),
    translated_at timestamptz,
    claim_id uuid,
    claimed_until timestamptz,
    CONSTRAINT ck_translation_completion CHECK
        ((translated_text IS NULL AND runtime_revision IS NULL AND translated_at IS NULL)
         OR (translated_text IS NOT NULL AND runtime_revision IS NOT NULL AND btrim(runtime_revision) <> '' AND translated_at IS NOT NULL)),
    CONSTRAINT ck_translation_claim CHECK ((claim_id IS NULL) = (claimed_until IS NULL)),
    CONSTRAINT ck_translation_fingerprint CHECK (fingerprint = catalogue.spanish_source_fingerprint(source_text))
);
CREATE TABLE catalogue.game_summary_translation (
    game_id uuid PRIMARY KEY REFERENCES catalogue.game(game_id),
    fingerprint varchar(64) NOT NULL REFERENCES catalogue.content_translation(fingerprint)
);
CREATE TABLE catalogue.curated_taxonomy_label (
    taxonomy varchar(16) NOT NULL CHECK (taxonomy IN ('genre','game_mode')),
    provider varchar(100) NOT NULL,
    provider_id varchar(200) NOT NULL,
    spanish_label varchar(200) NOT NULL CHECK (btrim(spanish_label) <> ''),
    PRIMARY KEY (taxonomy, provider, provider_id)
);
INSERT INTO catalogue.curated_taxonomy_label VALUES
    ('genre','IGDB','2','Aventura gráfica'), ('genre','IGDB','4','Lucha'),
    ('genre','IGDB','5','Disparos'), ('genre','IGDB','7','Música'),
    ('genre','IGDB','8','Plataformas'), ('genre','IGDB','9','Puzles'),
    ('genre','IGDB','10','Carreras'), ('genre','IGDB','11','Estrategia en tiempo real'),
    ('genre','IGDB','12','Rol (RPG)'), ('genre','IGDB','13','Simulación'),
    ('genre','IGDB','14','Deportes'), ('genre','IGDB','15','Estrategia'),
    ('genre','IGDB','16','Estrategia por turnos'), ('genre','IGDB','24','Táctica'),
    ('genre','IGDB','25','Hack and slash'), ('genre','IGDB','26','Preguntas y respuestas'),
    ('genre','IGDB','30','Pinball'), ('genre','IGDB','31','Aventura'),
    ('genre','IGDB','32','Indie'), ('genre','IGDB','33','Arcade'),
    ('genre','IGDB','34','Novela visual'), ('genre','IGDB','35','Cartas y juegos de mesa'),
    ('genre','IGDB','36','MOBA'),
    ('game_mode','IGDB','1','Un jugador'), ('game_mode','IGDB','2','Multijugador'),
    ('game_mode','IGDB','3','Cooperativo'), ('game_mode','IGDB','4','Pantalla dividida'),
    ('game_mode','IGDB','5','Multijugador masivo en línea'), ('game_mode','IGDB','6','Battle royale');
DO $$
DECLARE taxonomy text;
BEGIN
    FOREACH taxonomy IN ARRAY ARRAY['genre','game_mode'] LOOP
        EXECUTE format('ALTER TABLE catalogue.%I ADD COLUMN source_label varchar(200),
            ADD COLUMN label_origin varchar(16) NOT NULL DEFAULT ''source'',
            ADD COLUMN translation_fingerprint varchar(64) REFERENCES catalogue.content_translation(fingerprint),
            ADD CONSTRAINT ck_localized_label CHECK
                (label_origin IN (''source'',''curated'',''machine'') AND
                 (label_origin <> ''machine'' OR translation_fingerprint IS NOT NULL) AND
                 (label_origin <> ''curated'' OR translation_fingerprint IS NULL))', taxonomy);
        EXECUTE format('UPDATE catalogue.%1$I t SET source_label=t.display_name
            FROM catalogue.%1$I_external_reference r
            WHERE t.%1$s_id=r.%1$s_id AND r.provider=''IGDB''', taxonomy);
        EXECUTE format('UPDATE catalogue.%1$I t SET display_name=c.spanish_label, label_origin=''curated''
            FROM catalogue.%1$I_external_reference r, catalogue.curated_taxonomy_label c
            WHERE t.%1$s_id=r.%1$s_id AND c.taxonomy=%2$L
              AND c.provider=r.provider AND c.provider_id=r.provider_id', taxonomy, taxonomy);
    END LOOP;
END $$;
-- Select eligible identities before enrichment joins, even in sparse source populations.
CREATE INDEX ix_localization_summary_id ON catalogue.game_snapshot(game_id)
    WHERE summary_kind='sourced' AND summary_language='en'
      AND summary_source_kind='external_provider' AND summary_source_name='IGDB';
CREATE INDEX ix_localization_genre_id ON catalogue.genre(genre_id) WHERE source_label IS NOT NULL;
CREATE INDEX ix_localization_game_mode_id ON catalogue.game_mode(game_mode_id) WHERE source_label IS NOT NULL;
UPDATE catalogue.catalogue_publication SET catalogue_version=gen_random_uuid()::text;
COMMENT ON TABLE catalogue.content_translation IS
    'Content-addressed English-to-Spanish enrichment. PostgreSQL leases fence concurrent inference; completed content is immutable.';
COMMENT ON TABLE catalogue.game_summary_translation IS
    'Last valid derived translation. Source text/language/provenance remain in game_snapshot.';
