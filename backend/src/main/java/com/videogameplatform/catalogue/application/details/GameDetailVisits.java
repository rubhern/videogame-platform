package com.videogameplatform.catalogue.application.details;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GameDetailVisits {
    void record(UUID gameId, UUID visitId);

    List<Count> ranking(Instant start, Instant end);

    record Count(String gameId, long count) {}
}
