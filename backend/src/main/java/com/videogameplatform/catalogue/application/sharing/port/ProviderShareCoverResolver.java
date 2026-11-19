package com.videogameplatform.catalogue.application.sharing.port;

import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver.ResolvedProviderCover;

/**
 * Resolves an approved provider cover into its share rendition (ADR-0049): the same image and
 * size as the page's cover, in a format link-preview clients decode.
 */
@FunctionalInterface
public interface ProviderShareCoverResolver {

    ResolvedProviderCover resolveShare(String provider, String reference, String sourceUrl);
}
