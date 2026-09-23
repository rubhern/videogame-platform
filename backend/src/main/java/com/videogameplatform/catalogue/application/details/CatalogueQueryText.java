package com.videogameplatform.catalogue.application.details;

import com.videogameplatform.catalogue.domain.CatalogueSearchText;
import java.util.List;

/** Shared catalogue matching vocabulary without exporting catalogue domain internals. */
public final class CatalogueQueryText {
    private CatalogueQueryText() {}

    public static List<String> tokens(String text) {
        return CatalogueSearchText.of(text).tokens();
    }
}
