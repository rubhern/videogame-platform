package com.videogameplatform.catalogue.application.cover.port;

/** Persisted cover reference as read from the catalogue, before product cover policy runs. */
public sealed interface CatalogueCoverReference {

    record Product(String assetPath, String alternativeText) implements CatalogueCoverReference {}

    record Provider(String provider, String reference, String alternativeText, String sourceUrl)
            implements CatalogueCoverReference {}

    record Unavailable() implements CatalogueCoverReference {}
}
