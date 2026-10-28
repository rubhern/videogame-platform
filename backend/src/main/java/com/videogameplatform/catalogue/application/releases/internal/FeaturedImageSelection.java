package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver.Rendition;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver.ResolvedProviderImage;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedImage;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedLogo;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import java.util.Optional;

/**
 * The landscape image a ranked game presents under FEAT-003, shared by the featured releases of a
 * period (UC-010) and a platform's upcoming highlights (UC-020): the lead uses its stored hero
 * selection, every other game its stored card selection, each after ADR-0001 resolves the
 * approved rendition.
 */
final class FeaturedImageSelection {

    private final ProviderImageReferenceResolver imageResolver;

    FeaturedImageSelection(ProviderImageReferenceResolver imageResolver) {
        this.imageResolver = imageResolver;
    }

    /**
     * The lead's suitable hero image, else the designed fallback, never a cover; any other game's
     * suitable card image, else its provider cover shown whole, else the product fallback.
     */
    FeaturedImage select(
            boolean lead,
            Optional<MediaReference> heroImage,
            Optional<MediaReference> cardImage,
            CatalogueCover cover,
            String title) {
        return (lead ? heroImage : cardImage)
                .filter(
                        image ->
                                lead
                                        ? FeaturedMediaPolicy.landscape(
                                                image.width(), image.height(), image.transparent())
                                        : FeaturedMediaPolicy.cardSuitable(
                                                image.width(), image.height(), image.transparent()))
                .map(image -> providerImage(image, title))
                .orElseGet(() -> lead ? designedFallback(title) : coverImage(cover, title));
    }

    /** A logo stands in for the rendered title, so its alternative text is the title itself. */
    FeaturedLogo logo(MediaReference logo, String title) {
        ResolvedProviderImage resolved = resolve(logo, Rendition.LOGO);
        return new FeaturedLogo(
                resolved.url(),
                title,
                new CatalogueCover.Attribution(
                        resolved.attributionLabel(), resolved.attributionUrl()));
    }

    /**
     * The stored selection, presented as its proportions allow: only an opaque landscape image is
     * cropped to fill a frame, anything else is shown whole.
     */
    private FeaturedImage providerImage(MediaReference image, String title) {
        boolean fill =
                FeaturedMediaPolicy.presentation(image.width(), image.height(), image.transparent())
                        == FeaturedMediaPolicy.Presentation.FILL;
        ResolvedProviderImage large =
                resolve(image, fill ? Rendition.LANDSCAPE : Rendition.CONTAINED);
        ResolvedProviderImage compact =
                resolve(image, fill ? Rendition.LANDSCAPE_COMPACT : Rendition.CONTAINED);
        boolean artwork = image.kind() == FeaturedMediaPolicy.ImageKind.ARTWORK;
        return new FeaturedImage.Provider(
                artwork ? FeaturedImage.Kind.ARTWORK : FeaturedImage.Kind.SCREENSHOT,
                fill ? FeaturedImage.Presentation.FILL : FeaturedImage.Presentation.CONTAIN,
                large.url(),
                compact.url(),
                (artwork ? "Arte de " : "Captura de ") + title,
                new CatalogueCover.Attribution(large.attributionLabel(), large.attributionUrl()));
    }

    /**
     * A card without a selected image shows its provider cover as the last provider source: whole
     * in a designed treatment, never stretched or cropped. Without one either, the product
     * fallback.
     */
    private static FeaturedImage coverImage(CatalogueCover cover, String title) {
        return switch (cover) {
            case CatalogueCover.Provider provider ->
                    new FeaturedImage.Provider(
                            FeaturedImage.Kind.COVER,
                            FeaturedImage.Presentation.CONTAIN,
                            provider.url(),
                            provider.url(),
                            provider.alternativeText(),
                            provider.attribution());
            case CatalogueCover.Product _, CatalogueCover.Unavailable _ -> designedFallback(title);
        };
    }

    /**
     * The lead never presents a cover as its image: without landscape media it uses the
     * product-owned designed fallback, which the item's own cover may only light.
     */
    private static FeaturedImage designedFallback(String title) {
        return new FeaturedImage.Product("Imagen destacada no disponible de " + title);
    }

    private ResolvedProviderImage resolve(MediaReference image, Rendition rendition) {
        return imageResolver.resolve(
                image.provider(), image.reference(), image.sourceUrl(), rendition);
    }
}
