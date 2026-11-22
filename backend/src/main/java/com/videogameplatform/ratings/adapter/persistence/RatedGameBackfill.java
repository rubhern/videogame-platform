package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.catalogue.application.wishlist.RecordRatedGamesUseCase;
import com.videogameplatform.catalogue.application.wishlist.RecordRatedGamesUseCase.RatedGame;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Startup backfill of Catalogue's rated-games projection for ratings written before it existed
 * (ADR-0051). It walks every rating in keyset batches by user and game and records each batch,
 * keeping pairs already projected, so a repeated startup only reads. Rating writes keep the
 * projection current afterwards. A failure fails startup rather than serving a silently
 * incomplete Mis juegos count.
 */
public final class RatedGameBackfill implements ApplicationRunner {

    private static final int BATCH = RecordRatedGamesUseCase.MAX_BATCH;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final RecordRatedGamesUseCase ratedGames;

    public RatedGameBackfill(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations transaction,
            RecordRatedGamesUseCase ratedGames) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.ratedGames = ratedGames;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        UUID afterUser = new UUID(0, 0);
        UUID afterGame = new UUID(0, 0);
        while (true) {
            List<RatedGame> batch =
                    jdbc.query(
                            """
                            SELECT user_id, game_id FROM ratings.rating
                            WHERE (user_id, game_id) > (:afterUser, :afterGame)
                            ORDER BY user_id, game_id LIMIT :batch
                            """,
                            Map.of("afterUser", afterUser, "afterGame", afterGame, "batch", BATCH),
                            (resultSet, row) ->
                                    new RatedGame(resultSet.getString(1), resultSet.getString(2)));
            if (batch.isEmpty()) {
                return;
            }
            transaction.executeWithoutResult(status -> ratedGames.backfill(batch));
            RatedGame last = batch.getLast();
            afterUser = UUID.fromString(last.userId());
            afterGame = UUID.fromString(last.gameId());
        }
    }
}
