package com.videogameplatform.catalogue.application.port;

import java.net.URI;

/** Resolves an approved provider cover reference without exposing provider policy to delivery. */
public interface ProviderCoverReferenceResolver {

    ResolvedProviderCover resolve(String provider, String reference, String sourceUrl);

    record ResolvedProviderCover(URI url, String attributionLabel, URI attributionUrl) {}
}
