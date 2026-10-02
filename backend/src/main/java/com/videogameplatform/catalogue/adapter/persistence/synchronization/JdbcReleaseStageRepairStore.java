package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import com.videogameplatform.catalogue.application.synchronization.port.ReleaseStageRepairStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** Indexed known-Game selection, including Games with known stages and stale releases. */
public final class JdbcReleaseStageRepairStore implements ReleaseStageRepairStore {
    private final NamedParameterJdbcOperations jdbc;
    private final String provider;

    public JdbcReleaseStageRepairStore(NamedParameterJdbcOperations jdbc, String provider) {
        this.jdbc = jdbc;
        this.provider = provider;
    }

    @Override
    public List<Candidate> knownGamesAfter(UUID after, int limit) {
        if (limit < 1 || limit > 101) {
            throw new IllegalArgumentException("Invalid repair page size");
        }
        return jdbc.query(
                """
            SELECT g.game_id,g.provider_id FROM catalogue.game_external_reference g
            JOIN catalogue.game_snapshot s ON s.game_id=g.game_id
            WHERE g.provider=:provider AND g.provider_entity_type='game' AND g.game_id>:after AND s.game_id>:after
            ORDER BY g.game_id LIMIT :limit
            """,
                Map.of("provider", provider, "after", after, "limit", limit),
                (rs, row) ->
                        new Candidate(
                                rs.getObject("game_id", UUID.class), rs.getString("provider_id")));
    }

    @Override
    public Summary summary() {
        return jdbc.queryForObject(
                """
            SELECT total,supported,total-supported AS unsupported,games FROM
            (SELECT count(*) AS total FROM catalogue.release_snapshot WHERE release_stage='unknown') t
            CROSS JOIN (SELECT count(*) AS supported FROM catalogue.release_snapshot s
                WHERE s.release_stage='unknown' AND s.source_kind='external_provider'
                AND s.source_name=:provider AND s.source_entity_type='release_date'
                AND EXISTS (SELECT 1 FROM catalogue.release_external_reference r
                    WHERE r.release_id=s.release_id AND r.game_id=s.game_id AND r.provider=:provider)
                AND EXISTS (SELECT 1 FROM catalogue.game_external_reference g
                    WHERE g.game_id=s.game_id AND g.provider=:provider AND g.provider_entity_type='game')) p
            CROSS JOIN (SELECT count(*) AS games FROM catalogue.game_external_reference g
                JOIN catalogue.game_snapshot s ON s.game_id=g.game_id
                WHERE g.provider=:provider AND g.provider_entity_type='game') k
            """,
                Map.of("provider", provider),
                (rs, row) ->
                        new Summary(
                                rs.getLong("total"),
                                rs.getLong("supported"),
                                rs.getLong("unsupported"),
                                rs.getLong("games")));
    }
}
