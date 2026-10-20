package com.videogameplatform.catalogue.application.preferences.internal;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.preferences.ListPlatformsUseCase;
import com.videogameplatform.catalogue.application.preferences.port.SelectablePlatformsReadPort;

/** Framework-independent implementation of the selectable platform list. */
public final class PlatformListService implements ListPlatformsUseCase {
    private final SelectablePlatformsReadPort read;

    public PlatformListService(SelectablePlatformsReadPort read) {
        this.read = read;
    }

    @Override
    public PlatformList list() {
        PlatformList list =
                read.find(MAX_PLATFORMS + 1).orElseThrow(CatalogueNotReadyException::new);
        // The taxonomy is bounded; a list beyond the bound fails rather than hiding choices.
        if (list.platforms().size() > MAX_PLATFORMS) {
            throw new CatalogueDataInvalidException(
                    new IllegalStateException("The selectable platform bound was exceeded"));
        }
        return list;
    }
}
