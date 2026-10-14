package com.videogameplatform.catalogue.application.releases;

import java.util.Map;
import java.util.Set;

/** Only the selected discovery page's internal Game identities; absent entries have no score. */
public interface GetExternalPlayerScoresUseCase {
    int MAX_GAMES = 100;

    Map<String, ExternalPlayerScore> get(Set<String> gameIds);
}
