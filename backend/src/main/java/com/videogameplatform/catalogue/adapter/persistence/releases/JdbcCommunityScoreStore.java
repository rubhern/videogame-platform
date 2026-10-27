package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase.CommunityScore;
import com.videogameplatform.catalogue.application.releases.port.CommunityScoreStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/**
 * Catalogue-owned community score projection. It opens no transaction of its own: each statement
 * joins the Ratings transaction that changed the score, so both commit or roll back together.
 */
public final class JdbcCommunityScoreStore implements CommunityScoreStore {

    private final NamedParameterJdbcOperations jdbc;

    public JdbcCommunityScoreStore(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replace(UUID gameId, CommunityScore score) {
        if (score.ratingCount() == 0) {
            jdbc.update(
                    "DELETE FROM catalogue.game_community_score WHERE game_id = :game",
                    Map.of("game", gameId));
            return;
        }
        jdbc.update(
                """
                INSERT INTO catalogue.game_community_score
                    (game_id, mean, exact_mean, rating_count)
                VALUES (:game, :mean, :exactMean, :count)
                ON CONFLICT (game_id) DO UPDATE
                SET mean = EXCLUDED.mean,
                    exact_mean = EXCLUDED.exact_mean,
                    rating_count = EXCLUDED.rating_count
                WHERE (catalogue.game_community_score.mean,
                       catalogue.game_community_score.exact_mean,
                       catalogue.game_community_score.rating_count)
                      IS DISTINCT FROM (EXCLUDED.mean, EXCLUDED.exact_mean, EXCLUDED.rating_count)
                """,
                new MapSqlParameterSource("game", gameId)
                        .addValue("mean", score.mean())
                        .addValue("exactMean", score.exactMean())
                        .addValue("count", score.ratingCount()));
    }

    @Override
    public List<UUID> unprojected(List<UUID> gameIds) {
        return jdbc.query(
                """
                SELECT requested.game_id
                FROM unnest(CAST(:games AS uuid[])) AS requested(game_id)
                WHERE NOT EXISTS (
                    SELECT 1 FROM catalogue.game_community_score score
                    WHERE score.game_id = requested.game_id AND score.exact_mean IS NOT NULL)
                ORDER BY requested.game_id
                """,
                Map.of("games", gameIds.toArray(UUID[]::new)),
                (resultSet, row) -> resultSet.getObject(1, UUID.class));
    }
}
