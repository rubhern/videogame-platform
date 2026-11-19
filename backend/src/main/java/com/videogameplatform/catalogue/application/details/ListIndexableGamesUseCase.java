package com.videogameplatform.catalogue.application.details;

import java.util.List;

/**
 * Bounded, deterministic partitions of the current publication's games for search-engine
 * discovery (#537). Games are ordered by their identifier alone, which is unique within a
 * publication, so a partition is one keyset range and never a whole-catalogue read.
 */
public interface ListIndexableGamesUseCase {

    /** The largest partition a reader may ask for: the sitemap protocol's URL limit. */
    int MAX_PARTITION_SIZE = 50_000;

    /** The most partitions one listing returns: the sitemap index protocol's limit. */
    int MAX_PARTITIONS = 50_000;

    /**
     * The first game identifier of every partition of {@code partitionSize} games, in order. It is
     * empty when the current publication has no game.
     */
    List<String> partitionStarts(int partitionSize);

    /**
     * At most {@code partitionSize} games whose identifier is greater than or equal to {@code
     * startGameId}, in identifier order. A malformed identifier is an empty partition.
     */
    List<IndexableGame> partition(String startGameId, int partitionSize);
}
