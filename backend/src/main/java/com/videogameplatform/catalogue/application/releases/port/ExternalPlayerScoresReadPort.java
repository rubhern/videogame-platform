package com.videogameplatform.catalogue.application.releases.port;

import com.videogameplatform.catalogue.application.releases.ExternalPlayerScore;
import java.util.Map;
import java.util.Set;

public interface ExternalPlayerScoresReadPort {
    Map<String, ExternalPlayerScore> read(Set<String> gameIds);
}
