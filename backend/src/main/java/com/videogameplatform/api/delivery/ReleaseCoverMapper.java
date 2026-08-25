package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.model.Attribution;
import com.videogameplatform.api.generated.model.Cover;
import com.videogameplatform.api.generated.model.FallbackCover;
import com.videogameplatform.api.generated.model.ProviderCover;
import com.videogameplatform.catalogue.application.BrowseReleasesResult;
import org.springframework.stereotype.Component;

/** Maps provider-independent cover states to their HTTP presentation. */
@Component
final class ReleaseCoverMapper {

    private static final String FALLBACK_COVER_PATH = "/assets/covers/fallback.svg";
    private static final String FALLBACK_ALTERNATIVE_TEXT = "Carátula oficial no disponible";

    Cover toResponse(BrowseReleasesResult.Cover source) {
        return switch (source) {
            case BrowseReleasesResult.ProviderCover provider ->
                    new ProviderCover(
                            "provider",
                            provider.url(),
                            provider.alternativeText(),
                            new Attribution(
                                    provider.attribution().label(),
                                    provider.attribution().sourceUrl()));
            case BrowseReleasesResult.ProductCover product ->
                    fallback(product.assetPath(), product.alternativeText());
            case BrowseReleasesResult.UnavailableCover unavailable ->
                    fallback(FALLBACK_COVER_PATH, FALLBACK_ALTERNATIVE_TEXT);
        };
    }

    private static FallbackCover fallback(String assetPath, String alternativeText) {
        return new FallbackCover("fallback", assetPath, alternativeText, null);
    }
}
