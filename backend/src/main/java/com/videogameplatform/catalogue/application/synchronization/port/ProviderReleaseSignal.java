package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * The only lifecycle claim a provider record is trusted to make directly.
 *
 * <p>Released versus scheduled is a time-dependent product decision and belongs to the
 * application clock (REL-011), never to the provider or the adapter.
 */
public enum ProviderReleaseSignal {
    NONE,
    CANCELLED,
    DELAYED
}
