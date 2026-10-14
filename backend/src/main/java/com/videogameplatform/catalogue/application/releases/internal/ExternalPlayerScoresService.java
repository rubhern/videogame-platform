package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.releases.ExternalPlayerScore;
import com.videogameplatform.catalogue.application.releases.GetExternalPlayerScoresUseCase;
import com.videogameplatform.catalogue.application.releases.port.ExternalPlayerScoresReadPort;
import java.util.Map;
import java.util.Set;

public final class ExternalPlayerScoresService implements GetExternalPlayerScoresUseCase {
    private final ExternalPlayerScoresReadPort read;

    public ExternalPlayerScoresService(ExternalPlayerScoresReadPort read) {
        this.read = read;
    }

    @Override
    public Map<String, ExternalPlayerScore> get(Set<String> gameIds) {
        if (gameIds.size() > MAX_GAMES)
            throw new IllegalArgumentException("Score batch exceeds discovery page bound");
        return gameIds.isEmpty() ? Map.of() : Map.copyOf(read.read(Set.copyOf(gameIds)));
    }
}
