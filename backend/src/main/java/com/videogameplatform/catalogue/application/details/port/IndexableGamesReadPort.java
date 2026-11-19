package com.videogameplatform.catalogue.application.details.port;

import com.videogameplatform.catalogue.application.details.IndexableGame;
import java.util.List;
import java.util.UUID;

/** Keyset reads of the current publication's games in identifier order. */
public interface IndexableGamesReadPort {

    /** At most {@code maxPartitions} partition starts, each {@code partitionSize} games apart. */
    List<String> partitionStarts(int partitionSize, int maxPartitions);

    /** At most {@code limit} games from {@code startGameId} inclusive, in identifier order. */
    List<IndexableGame> partition(UUID startGameId, int limit);
}
