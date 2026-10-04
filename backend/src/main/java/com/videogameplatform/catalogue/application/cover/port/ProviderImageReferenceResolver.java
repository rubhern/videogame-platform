package com.videogameplatform.catalogue.application.cover.port;

import java.net.URI;

/**
 * Resolves an approved provider image reference into one delivery rendition without exposing
 * provider policy to delivery (ADR-0001). Each rendition maps to a single allowlisted provider size
 * and extension; nothing else is ever constructed.
 */
public interface ProviderImageReferenceResolver {

    ResolvedProviderImage resolve(
            String provider, String reference, String sourceUrl, Rendition rendition);

    /** The renditions featured discovery presents. */
    enum Rendition {
        /** A landscape image at the scale of the featured release of the month. */
        LANDSCAPE,
        /** A landscape image at the scale of the other featured releases. */
        LANDSCAPE_COMPACT,
        /** An image shown whole, its proportions kept, at either scale. */
        CONTAINED,
        /** A transparent game logo. */
        LOGO
    }

    record ResolvedProviderImage(URI url, String attributionLabel, URI attributionUrl) {}
}
