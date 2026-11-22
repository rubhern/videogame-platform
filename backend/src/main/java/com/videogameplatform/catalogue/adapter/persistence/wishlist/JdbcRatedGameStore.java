package com.videogameplatform.catalogue.adapter.persistence.wishlist;

import com.videogameplatform.catalogue.application.wishlist.port.RatedGameStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/**
 * Catalogue-owned rated-games projection. It opens no transaction of its own: each statement joins
 * the Ratings transaction that changed the rating, so both commit or roll back together.
 */
public final class JdbcRatedGameStore implements RatedGameStore {

    private final NamedParameterJdbcOperations jdbc;

    public JdbcRatedGameStore(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void rated(UUID userId, UUID gameId) {
        jdbc.update(
                """
                INSERT INTO catalogue.library_rated_game (user_id, game_id)
                VALUES (:owner, :game) ON CONFLICT DO NOTHING
                """,
                Map.of("owner", userId, "game", gameId));
    }

    @Override
    public void unrated(UUID userId, UUID gameId) {
        jdbc.update(
                "DELETE FROM catalogue.library_rated_game WHERE user_id = :owner AND game_id = :game",
                Map.of("owner", userId, "game", gameId));
    }

    @Override
    public void backfill(List<Pair> pairs) {
        jdbc.update(
                """
                INSERT INTO catalogue.library_rated_game (user_id, game_id)
                SELECT p.owner, p.game
                FROM unnest(CAST(:owners AS uuid[]), CAST(:games AS uuid[])) AS p(owner, game)
                -- A rating of a game Catalogue no longer holds has nothing to project.
                JOIN catalogue.game g ON g.game_id = p.game
                ON CONFLICT DO NOTHING
                """,
                Map.of(
                        "owners",
                        pairs.stream().map(Pair::userId).toArray(UUID[]::new),
                        "games",
                        pairs.stream().map(Pair::gameId).toArray(UUID[]::new)));
    }
}
