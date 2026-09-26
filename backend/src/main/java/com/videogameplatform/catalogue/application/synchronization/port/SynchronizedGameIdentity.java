package com.videogameplatform.catalogue.application.synchronization.port;

import java.util.UUID;

/**
 * Product-owned identity of the Game a synchronization step worked on, for diagnostics only.
 *
 * <p>{@code published} tells whether the identity exists in the catalogue. An unpublished
 * identity was assigned by the failed attempt and rolled back with it, so a later run assigns a
 * new one. Provider references and titles never belong here.
 */
public record SynchronizedGameIdentity(UUID gameId, String slug, boolean published) {

    public static SynchronizedGameIdentity published(UUID gameId, String slug) {
        return new SynchronizedGameIdentity(gameId, slug, true);
    }

    public static SynchronizedGameIdentity unpublished(UUID gameId, String slug) {
        return new SynchronizedGameIdentity(gameId, slug, false);
    }
}
