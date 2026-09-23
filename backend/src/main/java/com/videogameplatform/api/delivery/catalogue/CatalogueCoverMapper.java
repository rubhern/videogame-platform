package com.videogameplatform.api.delivery.catalogue;

import com.videogameplatform.api.generated.model.Attribution;
import com.videogameplatform.api.generated.model.Cover;
import com.videogameplatform.api.generated.model.FallbackCover;
import com.videogameplatform.api.generated.model.ProviderCover;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import org.springframework.stereotype.Component;

/** Maps provider-independent cover states to their HTTP presentation. */
@Component
public final class CatalogueCoverMapper {

    private static final String FALLBACK_COVER_PATH = "/assets/covers/fallback.svg";
    private static final String FALLBACK_ALTERNATIVE_TEXT = "Carátula oficial no disponible";

    public Cover toResponse(CatalogueCover source) {
        return switch (source) {
            case CatalogueCover.Provider provider ->
                    new ProviderCover(
                            "provider",
                            provider.url(),
                            provider.alternativeText(),
                            new Attribution(
                                    provider.attribution().label(),
                                    provider.attribution().sourceUrl()));
            case CatalogueCover.Product product ->
                    fallback(product.assetPath(), product.alternativeText());
            case CatalogueCover.Unavailable unavailable ->
                    fallback(FALLBACK_COVER_PATH, FALLBACK_ALTERNATIVE_TEXT);
        };
    }

    private static FallbackCover fallback(String assetPath, String alternativeText) {
        return new FallbackCover("fallback", assetPath, alternativeText, null);
    }
}
