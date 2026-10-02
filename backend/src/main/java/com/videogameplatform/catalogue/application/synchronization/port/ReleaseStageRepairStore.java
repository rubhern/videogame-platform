package com.videogameplatform.catalogue.application.synchronization.port;

import java.util.List;
import java.util.UUID;

/** Bounded keyset selection only; every repair write uses the normal synchronization store. */
public interface ReleaseStageRepairStore {
    List<Candidate> knownGamesAfter(UUID after, int limit);

    Summary summary();

    record Candidate(UUID gameId, String gameReference) {}

    record Summary(
            long totalUnknown,
            long repairableUnknown,
            long withoutSupportedReference,
            long knownGames) {}
}
