-- Synthetic provider references keep the deterministic prototype Games addressable by
-- synchronization tests. They are fixtures only and must never be treated as real IGDB claims.
-- The prototype Games predate automatic discovery: they were created by hand as the
-- product journey fixture, and these references preserve their existing internal identities.
INSERT INTO catalogue.game_external_reference (
    game_id, provider, provider_entity_type, provider_id, provider_url
) VALUES
    ('30000000-0000-4000-8000-000000000001', 'IGDB', 'game', '9000001', NULL),
    ('30000000-0000-4000-8000-000000000002', 'IGDB', 'game', '9000002', NULL),
    ('30000000-0000-4000-8000-000000000003', 'IGDB', 'game', '9000003', NULL),
    ('30000000-0000-4000-8000-000000000004', 'IGDB', 'game', '9000004', NULL),
    ('30000000-0000-4000-8000-000000000005', 'IGDB', 'game', '9000005', NULL),
    ('30000000-0000-4000-8000-000000000006', 'IGDB', 'game', '9000006', NULL),
    ('30000000-0000-4000-8000-000000000007', 'IGDB', 'game', '9000007', NULL),
    ('30000000-0000-4000-8000-000000000008', 'IGDB', 'game', '9000008', NULL),
    ('30000000-0000-4000-8000-000000000009', 'IGDB', 'game', '9000009', NULL),
    ('30000000-0000-4000-8000-00000000000a', 'IGDB', 'game', '9000010', NULL),
    ('30000000-0000-4000-8000-00000000000b', 'IGDB', 'game', '9000011', NULL),
    ('30000000-0000-4000-8000-00000000000c', 'IGDB', 'game', '9000012', NULL);
