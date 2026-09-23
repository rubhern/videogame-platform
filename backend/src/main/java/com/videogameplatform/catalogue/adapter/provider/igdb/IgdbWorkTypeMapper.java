package com.videogameplatform.catalogue.adapter.provider.igdb;

import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import java.util.Locale;
import java.util.Map;

/**
 * Maps IGDB's own work taxonomy onto the product's closed vocabulary.
 *
 * <p>This is the only place IGDB category names exist. Keeping the translation here means the
 * import policy is stated in product terms and can be reviewed without knowing IGDB, and an
 * unrecognised provider category becomes {@link ProviderWorkType#UNKNOWN} rather than being
 * guessed into something importable.
 */
final class IgdbWorkTypeMapper {

    private static final Map<String, ProviderWorkType> TYPES =
            Map.ofEntries(
                    Map.entry("main_game", ProviderWorkType.MAIN_GAME),
                    Map.entry("remake", ProviderWorkType.REMAKE),
                    Map.entry("remaster", ProviderWorkType.REMASTER),
                    Map.entry("standalone_expansion", ProviderWorkType.STANDALONE_EXPANSION),
                    Map.entry("expanded_game", ProviderWorkType.EXPANSION),
                    Map.entry("expansion", ProviderWorkType.EXPANSION),
                    Map.entry("dlc_addon", ProviderWorkType.ADD_ON),
                    Map.entry("episode", ProviderWorkType.EPISODE),
                    Map.entry("season", ProviderWorkType.SEASON),
                    Map.entry("bundle", ProviderWorkType.BUNDLE),
                    Map.entry("pack", ProviderWorkType.PACK),
                    Map.entry("update", ProviderWorkType.UPDATE),
                    Map.entry("port", ProviderWorkType.PORT),
                    Map.entry("mod", ProviderWorkType.MOD),
                    Map.entry("fork", ProviderWorkType.FORK));

    private IgdbWorkTypeMapper() {}

    static ProviderWorkType map(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return ProviderWorkType.UNKNOWN;
        }
        String normalized =
                providerType.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return TYPES.getOrDefault(normalized, ProviderWorkType.UNKNOWN);
    }
}
