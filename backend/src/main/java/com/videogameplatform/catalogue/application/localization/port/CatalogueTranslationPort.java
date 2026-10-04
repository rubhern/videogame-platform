package com.videogameplatform.catalogue.application.localization.port;

/** Fixed English-to-Spanish acquisition boundary. Runtime details stay in its adapter. */
@FunctionalInterface
public interface CatalogueTranslationPort {
    Translation translate(String source);

    record Translation(String text, String revision) {}
}
