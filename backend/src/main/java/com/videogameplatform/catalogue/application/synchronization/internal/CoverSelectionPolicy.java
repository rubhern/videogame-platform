package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderCover;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CoverSelection;
import java.util.Optional;

/**
 * Which cover a snapshot publishes.
 *
 * <p>Approval here is the usage-mode approval of ADR-0001 and GAME-015, not a per-image human
 * review: the adapter has already checked that a provider reference has the allowlisted shape and
 * a usable attribution page, so a cover that reaches this policy is publishable.
 *
 * <p>Two rules follow from GAME-006 and CAT-003. Every visible game resolves to a cover, so an
 * import without a usable provider cover publishes the product-owned fallback. And a published
 * cover is never degraded by provider silence, so reconciliation with nothing usable leaves the
 * existing cover alone.
 *
 * <p>The alternative text is product-owned Spanish and describes the game, never the provider.
 */
public record CoverSelectionPolicy(String fallbackAssetPath, String fallbackSourceName) {

    public CoverSelectionPolicy {
        if (fallbackAssetPath == null || fallbackAssetPath.isBlank()) {
            throw new IllegalArgumentException("A product fallback cover asset path is required");
        }
        if (fallbackSourceName == null || fallbackSourceName.isBlank()) {
            throw new IllegalArgumentException("A product fallback cover source name is required");
        }
    }

    /** An imported game always publishes a cover: the provider's when usable, otherwise ours. */
    public CoverSelection forImport(String canonicalTitle, Optional<ProviderCover> cover) {
        return cover.map(provider -> providerCover(canonicalTitle, provider))
                .orElseGet(() -> fallback(canonicalTitle));
    }

    /** A known game changes its cover only when the provider offers a usable one. */
    public Optional<CoverSelection> forReconciliation(
            String canonicalTitle, Optional<ProviderCover> cover) {
        return cover.map(provider -> providerCover(canonicalTitle, provider));
    }

    public CoverSelection fallback(String canonicalTitle) {
        return new CoverSelection.ProductFallback(
                fallbackAssetPath,
                fallbackSourceName,
                "Portada no disponible de " + canonicalTitle);
    }

    private static CoverSelection providerCover(String canonicalTitle, ProviderCover cover) {
        return new CoverSelection.Provider(
                cover.reference(), cover.sourceUrl(), "Portada de " + canonicalTitle);
    }
}
