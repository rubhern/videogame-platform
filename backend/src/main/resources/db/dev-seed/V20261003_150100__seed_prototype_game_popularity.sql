-- Synthetic popularity evidence for the deterministic prototype catalogue (#151). The values are
-- fixtures, never real IGDB Visits, and only exist so the featured releases of a month can be
-- exercised without provider access. Observation times match the prototype synchronization, so
-- they are fresh for the browser gate clock pinned to 2026-08-13.
--
-- September 2026 features Marvel's Wolverine and October 2026 features Crimson Desert. The Witcher
-- IV keeps a signal without any qualifying release, which proves popularity alone never features a
-- game; every other prototype game has no signal at all.
INSERT INTO catalogue.game_popularity (
    game_id,
    visits,
    source_name,
    provider_calculated_at,
    observed_at
) VALUES
    ('30000000-0000-4000-8000-00000000000c', 0.0061, 'IGDB', '2026-08-09 00:00:00+00', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-00000000000a', 0.0042, 'IGDB', '2026-08-09 00:00:00+00', '2026-08-09 10:00:00+00'),
    ('30000000-0000-4000-8000-000000000008', 0.0093, 'IGDB', '2026-08-09 00:00:00+00', '2026-08-09 10:00:00+00');
