package com.videogameplatform.catalogue.application.preferences.port;

import com.videogameplatform.catalogue.application.preferences.ListPlatformsUseCase.PlatformList;
import java.util.Optional;

/** Outbound query port for the platforms the current publication's releases use. */
public interface SelectablePlatformsReadPort {

    /**
     * At most {@code limit} platforms ordered by name and then identifier, so a caller asking for
     * one more than it accepts can tell a complete list from an exceeded bound.
     *
     * @return empty when no catalogue publication exists yet
     */
    Optional<PlatformList> find(int limit);
}
