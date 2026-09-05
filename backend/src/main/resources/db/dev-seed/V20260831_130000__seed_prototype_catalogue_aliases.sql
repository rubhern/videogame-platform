-- Deterministic prototype aliases for UC-002. They are disposable development data, not
-- provider truth, and they do not widen the approved MVP catalogue boundary: no game is
-- added, only alternative titles resolving to games already published in this snapshot.
--
-- The set deliberately exercises every reviewed search behaviour:
--   * an alias-only match, because `The Witcher 4` shares no token with `The Witcher IV`;
--   * a localized alias with product provenance, as required for Spanish/Japanese titles;
--   * an ASCII alias for a diacritic canonical title;
--   * a pending alias that must never be searchable.
INSERT INTO catalogue.game_alias (
    publication_id,
    game_id,
    alias,
    alias_kind,
    language_tag,
    approval_status,
    source_kind,
    source_name
) VALUES
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000008', 'The Witcher 4', 'alternative', NULL, 'approved', 'product_curated', 'VideoGame Platform curation'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000005', 'Biohazard Requiem', 'localized', 'ja', 'approved', 'product_curated', 'VideoGame Platform curation'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000005', 'Resident Evil 9', 'alternative', NULL, 'approved', 'product_curated', 'VideoGame Platform curation'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000003', 'Ghost of Yotei', 'alternative', NULL, 'approved', 'product_curated', 'VideoGame Platform curation'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000004', 'Silksong', 'alternative', NULL, 'approved', 'product_curated', 'VideoGame Platform curation'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-00000000000c', 'Wolverine', 'alternative', NULL, 'approved', 'product_curated', 'VideoGame Platform curation'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', 'Death Stranding 2', 'alternative', NULL, 'approved', 'product_curated', 'VideoGame Platform curation'),
    ('00000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000009', 'Samus Returns 2', 'alternative', NULL, 'pending', 'product_curated', 'VideoGame Platform curation');
