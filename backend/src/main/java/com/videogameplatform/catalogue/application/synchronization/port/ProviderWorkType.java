package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * What kind of work a provider record represents, in product vocabulary.
 *
 * <p>Providers classify far more finely than this product models. The adapter maps the provider's
 * own taxonomy onto this closed set, so the import policy can be stated, tested and changed
 * without any provider concept leaking inward (EXT-002). Anything the adapter cannot place lands
 * on {@link #UNKNOWN} rather than being guessed into a supported kind.
 */
public enum ProviderWorkType {
    /** A work that stands on its own. */
    MAIN_GAME,
    /** A rebuilt version released as its own work. */
    REMAKE,
    /** A reissued version released as its own work. */
    REMASTER,
    /** An expansion playable without the original, released as its own work. */
    STANDALONE_EXPANSION,
    /** Content that requires a parent work. */
    ADD_ON,
    /** An expansion that requires its parent work. */
    EXPANSION,
    /** One instalment of a serialized work. */
    EPISODE,
    /** A set of instalments of a serialized work. */
    SEASON,
    /** Commercial packaging of several works. */
    BUNDLE,
    /** Commercial packaging of content, not a work. */
    PACK,
    /** A patch or revision, not a work. */
    UPDATE,
    /** The same work carried to another platform; the release model already expresses this. */
    PORT,
    /** A community modification of another work. */
    MOD,
    /** A derivative branch of another work. */
    FORK,
    /** The provider stated a kind this product does not recognise. */
    UNKNOWN
}
