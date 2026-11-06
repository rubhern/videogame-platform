package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which provider works become catalogue members (CAT-002, CAT-006).
 *
 * <p>The allowlist is deliberately small and stated positively. A work is imported only when this
 * product models it as something a person finds and rates on its own:
 *
 * <ul>
 *   <li>a main work, a remake, a remaster, or an expansion that stands alone: each is a distinct
 *       thing to play and to score;
 *   <li>a port or an expanded game: IGDB gives each its own record, whose releases the parent never
 *       holds, so deferring one hid the launch from the whole product (#456). It becomes a Game of
 *       its own, with no relationship to its parent, which stays with #449;
 *   <li>everything else is deferred. Add-ons, expansions, episodes and seasons depend on a parent
 *       work the product does not model separately; bundles, packs and updates are commercial
 *       packaging rather than works; and mods and forks are not first-party works.
 * </ul>
 *
 * <p>Deferring is expected behaviour, not failure. Widening the catalogue is a change to this set,
 * not to the synchronization mechanism.
 */
public final class GameImportPolicy {

    private static final Set<ProviderWorkType> IMPORTABLE =
            EnumSet.of(
                    ProviderWorkType.MAIN_GAME,
                    ProviderWorkType.REMAKE,
                    ProviderWorkType.REMASTER,
                    ProviderWorkType.STANDALONE_EXPANSION,
                    ProviderWorkType.PORT,
                    ProviderWorkType.EXPANDED_GAME);

    private GameImportPolicy() {}

    public static Decision evaluate(ProviderWork work) {
        if (!IMPORTABLE.contains(work.type())) {
            return Decision.DEFERRED_UNSUPPORTED_TYPE;
        }
        if (work.title() == null || work.title().isBlank()) {
            return Decision.DEFERRED_UNUSABLE_TITLE;
        }
        return Decision.ACCEPTED;
    }

    public static Set<ProviderWorkType> importableTypes() {
        return EnumSet.copyOf(IMPORTABLE);
    }

    /** Closed outcome vocabulary; the deferred reasons are bounded metric dimensions. */
    public enum Decision {
        ACCEPTED,
        DEFERRED_UNSUPPORTED_TYPE,
        DEFERRED_UNUSABLE_TITLE;

        public boolean accepted() {
            return this == ACCEPTED;
        }
    }
}
