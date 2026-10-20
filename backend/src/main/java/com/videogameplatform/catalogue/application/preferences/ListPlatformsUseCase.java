package com.videogameplatform.catalogue.application.preferences;

import java.util.List;

/** Public application contract for the platforms a visitor may choose as preferred. */
public interface ListPlatformsUseCase {

    /**
     * The upper bound of the selectable taxonomy. A catalogue holding more fails the read rather
     * than truncating the choices.
     */
    int MAX_PLATFORMS = 500;

    /**
     * Every platform at least one current release uses, ordered by name and then identifier.
     *
     * @throws com.videogameplatform.catalogue.application.CatalogueNotReadyException when no
     *     catalogue publication exists yet
     */
    PlatformList list();

    /** The selectable platforms of one catalogue publication. */
    record PlatformList(String publicationVersion, List<PlatformOption> platforms) {
        public PlatformList {
            platforms = List.copyOf(platforms);
        }
    }
}
