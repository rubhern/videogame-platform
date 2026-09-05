package com.videogameplatform.catalogue.application.cover;

import java.net.URI;

/**
 * Provider-independent primary-cover state shared by every catalogue read.
 *
 * <p>ADR-0001 is applied once, before delivery: an approved provider reference keeps its
 * attribution, a product-owned asset is served directly, and anything else degrades to the
 * product fallback without hiding the game.
 */
public sealed interface CatalogueCover {

    /** Approved direct provider CDN reference with its mandatory attribution. */
    record Provider(URI url, String alternativeText, Attribution attribution)
            implements CatalogueCover {}

    /** Product-owned cover asset served from this application. */
    record Product(String assetPath, String alternativeText) implements CatalogueCover {}

    /** No displayable approved cover exists; delivery substitutes the product fallback. */
    record Unavailable() implements CatalogueCover {}

    record Attribution(String label, URI sourceUrl) {}
}
