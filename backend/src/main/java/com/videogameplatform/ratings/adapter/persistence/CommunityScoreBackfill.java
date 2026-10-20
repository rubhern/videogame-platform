package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Startup backfill of the community score projection for games rated before it existed
 * (ADR-0029). It walks rated games in keyset batches, asks Catalogue which of them hold no score,
 * and projects each missing one under the same per-game lock a rating write takes. Rating writes
 * keep every projected score current afterwards, so a later startup only reads the batches. A
 * failure fails startup rather than serving a silently incomplete rating order.
 */
public final class CommunityScoreBackfill implements ApplicationRunner {

    private static final int BATCH = 200;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final RecordCommunityScoresUseCase scores;

    public CommunityScoreBackfill(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations transaction,
            RecordCommunityScoresUseCase scores) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.scores = scores;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        UUID after = new UUID(0, 0);
        while (true) {
            List<String> batch =
                    jdbc.query(
                            """
                            SELECT DISTINCT game_id FROM ratings.rating
                            WHERE game_id > :after ORDER BY game_id LIMIT :batch
                            """,
                            Map.of("after", after, "batch", BATCH),
                            (resultSet, row) -> resultSet.getString(1));
            if (batch.isEmpty()) {
                return;
            }
            scores.unprojected(batch).stream().sorted().forEach(this::project);
            after = UUID.fromString(batch.getLast());
        }
    }

    private void project(String gameId) {
        transaction.executeWithoutResult(
                status -> {
                    CommunityScoreLock.acquire(jdbc, gameId);
                    var statistics = RatingStatisticsQuery.read(jdbc, gameId);
                    scores.replace(
                            gameId,
                            new RecordCommunityScoresUseCase.CommunityScore(
                                    statistics.mean(), statistics.count()));
                });
    }
}
