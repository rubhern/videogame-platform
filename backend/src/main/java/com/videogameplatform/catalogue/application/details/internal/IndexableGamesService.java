package com.videogameplatform.catalogue.application.details.internal;

import com.videogameplatform.catalogue.application.details.IndexableGame;
import com.videogameplatform.catalogue.application.details.ListIndexableGamesUseCase;
import com.videogameplatform.catalogue.application.details.port.IndexableGamesReadPort;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class IndexableGamesService implements ListIndexableGamesUseCase {
    private static final Pattern GAME_ID =
            Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private final IndexableGamesReadPort read;

    public IndexableGamesService(IndexableGamesReadPort read) {
        this.read = read;
    }

    @Override
    public List<String> partitionStarts(int partitionSize) {
        return read.partitionStarts(requireSize(partitionSize), MAX_PARTITIONS);
    }

    @Override
    public List<IndexableGame> partition(String startGameId, int partitionSize) {
        requireSize(partitionSize);
        // Only the canonical lowercase form a partition start is published in names a partition.
        if (startGameId == null || !GAME_ID.matcher(startGameId).matches()) {
            return List.of();
        }
        return read.partition(UUID.fromString(startGameId), partitionSize);
    }

    private static int requireSize(int partitionSize) {
        if (partitionSize < 1 || partitionSize > MAX_PARTITION_SIZE) {
            throw new IllegalArgumentException(
                    "partitionSize must be between 1 and " + MAX_PARTITION_SIZE);
        }
        return partitionSize;
    }
}
