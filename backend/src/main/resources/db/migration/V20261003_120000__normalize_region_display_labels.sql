-- #213: a Region's display label is product presentation (CAT-008). Before the product label
-- rule existed, runtime acquisition copied the raw provider descriptor into display_name (for
-- example 'asia' or 'new_zealand') and the pre-seeded regions kept English names that only a
-- frontend map translated. This one-time repair re-derives every stored label with the rule the
-- runtime now applies when a region is first acquired (catalogue.domain.RegionLabel): an approved
-- Spanish label for a known descriptor, otherwise a readable form of the descriptor.
--
-- Only display_name changes. region_id, code, provider external references and release foreign
-- keys are untouched, so identity, filtering and the code-based presentation order are unaffected.
-- The label is derived from the descriptor already stored in display_name; it never selects,
-- merges or resolves a region. A label that is already readable re-derives to itself.

UPDATE catalogue.region region
SET display_name = CASE
        WHEN approved.display_name IS NOT NULL THEN approved.display_name
        WHEN descriptor.words = '' THEN 'Región sin nombre'
        -- A lowercase or underscored descriptor is a technical token: its words are capitalized.
        WHEN position('_' IN region.display_name) > 0
            OR region.display_name !~ '[[:upper:]]' THEN initcap(descriptor.words)
        -- A name the provider already wrote for people keeps its spelling.
        ELSE btrim(regexp_replace(region.display_name, '[[:space:][:cntrl:]]+', ' ', 'g'))
    END
FROM (
    SELECT region_id,
           lower(btrim(regexp_replace(display_name, '[[:space:][:cntrl:]_-]+', ' ', 'g'))) AS words
    FROM catalogue.region
) AS descriptor
LEFT JOIN (VALUES
    ('worldwide', 'Mundial'),
    ('europe', 'Europa'),
    ('north america', 'Norteamérica'),
    ('japan', 'Japón'),
    ('new zealand', 'Nueva Zelanda'),
    ('korea', 'Corea'),
    ('brazil', 'Brasil')
) AS approved(words, display_name) ON approved.words = descriptor.words
WHERE descriptor.region_id = region.region_id
  AND region.code <> 'unknown';

-- The product-only sentinel for a release whose provider states no region has no descriptor.
UPDATE catalogue.region
SET display_name = 'Sin región confirmada'
WHERE code = 'unknown';
