package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.releases.ExternalPlayerScore;
import com.videogameplatform.catalogue.application.releases.port.ExternalPlayerScoresReadPort;
import com.videogameplatform.catalogue.domain.ExternalScore;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** One indexed query against the phase-1 current state; at most one row per selected Game. */
public final class JdbcExternalPlayerScoresReadAdapter implements ExternalPlayerScoresReadPort {
    private final NamedParameterJdbcOperations jdbc;

    public JdbcExternalPlayerScoresReadAdapter(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, ExternalPlayerScore> read(Set<String> gameIds) {
        try {
            return jdbc.query(
                    """
                    SELECT game_id, score, score_count, source_url, observed_at, provider_record_updated_at
                    FROM catalogue.game_external_score
                    WHERE game_id IN (:games) AND source_name = 'IGDB' AND score_kind = 'player'
                      AND score IS NOT NULL
                    """,
                    Map.of("games", gameIds.stream().map(UUID::fromString).toList()),
                    rs -> {
                        Map<String, ExternalPlayerScore> result = new HashMap<>();
                        while (rs.next()) {
                            var updated =
                                    rs.getObject(
                                            "provider_record_updated_at", OffsetDateTime.class);
                            var score =
                                    new ExternalScore(
                                            rs.getBigDecimal("score"), rs.getLong("score_count"));
                            result.put(
                                    rs.getString("game_id"),
                                    new ExternalPlayerScore(
                                            ExternalPlayerScore.Source.IGDB,
                                            score.value(),
                                            score.count(),
                                            URI.create(rs.getString("source_url")),
                                            rs.getObject("observed_at", OffsetDateTime.class)
                                                    .toInstant(),
                                            Optional.ofNullable(updated)
                                                    .map(OffsetDateTime::toInstant)));
                        }
                        return Map.copyOf(result);
                    });
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw new CatalogueReadException(exception);
        }
    }
}
