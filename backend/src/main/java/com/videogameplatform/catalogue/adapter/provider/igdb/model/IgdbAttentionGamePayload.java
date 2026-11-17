package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * The IGDB game fields the attention lookup reads: the game type and the Hypes of the version or
 * parent game the provider names, each expanded to its identity and Hypes.
 */
public record IgdbAttentionGamePayload(
        Long id, IgdbNamedValuePayload gameType, Parent versionParent, Parent parentGame) {

    /** A related game as the provider expands it. */
    public record Parent(Long id, java.math.BigDecimal hypes) {}
}
