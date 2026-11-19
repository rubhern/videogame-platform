package com.videogameplatform.catalogue.adapter.persistence.details;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.details.IndexableGame;
import com.videogameplatform.catalogue.application.details.port.IndexableGamesReadPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Current-publication games in identifier order through the game snapshot primary key {@code
 * (publication_id, game_id)}: a partition is one bounded index range, and the partition starts are
 * one index-only pass that returns a row per partition, never per game. Output columns never reuse
 * the name {@code game_id}: ORDER BY would bind to the text alias and sort without the index.
 */
public final class JdbcIndexableGamesReadAdapter implements IndexableGamesReadPort {
    private static final String PARTITION_STARTS =
            """
                SELECT game_id::text AS start_id
                FROM (
                    SELECT game_id, row_number() OVER (ORDER BY game_id) AS position
                    FROM catalogue.game_snapshot
                    WHERE publication_id = :publication
                ) ranked
                WHERE (position - 1) % :size = 0
                ORDER BY game_id
                LIMIT :max
                """;
    private static final String PARTITION =
            """
                SELECT game_id::text AS id, slug
                FROM catalogue.game_snapshot
                WHERE publication_id = :publication AND game_id >= :start
                ORDER BY game_id
                LIMIT :limit
                """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcIndexableGamesReadAdapter(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public List<String> partitionStarts(int partitionSize, int maxPartitions) {
        return CatalogueReadTransaction.execute(
                transaction,
                status ->
                        jdbc.query(
                                PARTITION_STARTS,
                                Map.of(
                                        "publication", currentPublication(),
                                        "size", partitionSize,
                                        "max", maxPartitions),
                                (rs, row) -> rs.getString("start_id")));
    }

    @Override
    public List<IndexableGame> partition(UUID startGameId, int limit) {
        return CatalogueReadTransaction.execute(
                transaction,
                status ->
                        jdbc.query(
                                PARTITION,
                                Map.of(
                                        "publication", currentPublication(),
                                        "start", startGameId,
                                        "limit", limit),
                                (rs, row) ->
                                        new IndexableGame(
                                                rs.getString("id"), rs.getString("slug"))));
    }

    private UUID currentPublication() {
        return UUID.fromString(
                CurrentPublicationReader.read(jdbc)
                        .orElseThrow(CatalogueNotReadyException::new)
                        .id());
    }
}
