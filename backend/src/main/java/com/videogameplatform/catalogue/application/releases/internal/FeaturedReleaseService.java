package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver.Rendition;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver.ResolvedProviderImage;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReadMapping;
import com.videogameplatform.catalogue.application.internal.CatalogueReleaseMapping;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.FeaturedMonthOutOfRangeException;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedImage;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedLogo;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.Selection;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.Item;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/** Framework-independent implementation of UC-010. */
public final class FeaturedReleaseService implements BrowseFeaturedReleasesUseCase {

    /** The month's featured release and up to five other featured releases. */
    static final int FEATURED_LIMIT = 6;

    private final FeaturedReleaseReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final ProviderImageReferenceResolver imageResolver;
    private final Clock clock;
    private final ReleaseBrowsePolicy browsePolicy;
    private final CatalogueFreshnessPolicy freshnessPolicy;

    public FeaturedReleaseService(
            FeaturedReleaseReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            ProviderImageReferenceResolver imageResolver,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy,
            CatalogueFreshnessPolicy freshnessPolicy) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.imageResolver = imageResolver;
        this.clock = clock;
        this.browsePolicy = browsePolicy;
        this.freshnessPolicy = freshnessPolicy;
    }

    @Override
    public FeaturedReleasesResult browse(Query query) {
        Instant evaluatedAt = clock.instant();
        LocalDate evaluatedOn = LocalDate.ofInstant(evaluatedAt, clock.getZone());
        YearMonth month = query.month().orElseGet(() -> YearMonth.from(evaluatedOn));
        // Featured discovery covers the current calendar year only; history is not browsed.
        if (month.getYear() != evaluatedOn.getYear()) {
            throw new FeaturedMonthOutOfRangeException();
        }
        BrowseReleasesResult.Window window =
                new BrowseReleasesResult.Window(month.atDay(1), month.atEndOfMonth());

        FeaturedReleaseReadPort.Result result =
                readPort.findFeaturedReleases(
                                new FeaturedReleaseReadPort.Criteria(
                                        window.from(),
                                        window.to(),
                                        FEATURED_LIMIT,
                                        browsePolicy.releaseGroupLimit()))
                        .orElseThrow(CatalogueNotReadyException::new);

        List<FeaturedReleasesResult.Item> items =
                java.util.stream.IntStream.range(0, result.items().size())
                        .mapToObj(
                                index ->
                                        toItem(
                                                result.items().get(index),
                                                index == 0,
                                                evaluatedAt,
                                                evaluatedOn))
                        .toList();
        return new FeaturedReleasesResult(
                month, evaluatedOn, window, selection(result, evaluatedAt), items);
    }

    /**
     * The oldest signal among the ranked games bounds how current the whole ranking is, so it alone
     * decides the selection's freshness.
     */
    private Selection selection(FeaturedReleaseReadPort.Result result, Instant evaluatedAt) {
        if (result.items().isEmpty()) {
            return result.qualifyingReleases()
                    ? new Selection.PopularityUnavailable()
                    : new Selection.NoQualifyingReleases();
        }
        Instant oldest =
                result.items().stream()
                        .map(Item::popularityObservedAt)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new Selection.Ranked(
                CatalogueReadMapping.toFreshness(freshnessPolicy.status(oldest, evaluatedAt)),
                oldest);
    }

    private FeaturedReleasesResult.Item toItem(
            Item item, boolean hero, Instant evaluatedAt, LocalDate evaluatedOn) {
        BrowseReleasesResult.Item release =
                new BrowseReleasesResult.Item(
                        item.gameId(),
                        item.slug(),
                        item.canonicalTitle(),
                        coverPolicy.resolve(item.cover()),
                        item.releases().stream()
                                .map(
                                        row ->
                                                CatalogueReleaseMapping.map(
                                                        row,
                                                        evaluatedAt,
                                                        evaluatedOn,
                                                        freshnessPolicy))
                                .toList(),
                        item.genres());
        return new FeaturedReleasesResult.Item(
                release,
                (hero ? item.image() : item.cardImage())
                        .filter(
                                image ->
                                        hero
                                                ? FeaturedMediaPolicy.landscape(
                                                        image.width(),
                                                        image.height(),
                                                        image.transparent())
                                                : FeaturedMediaPolicy.cardSuitable(
                                                        image.width(),
                                                        image.height(),
                                                        image.transparent()))
                        .map(image -> providerImage(image, item.canonicalTitle()))
                        .orElseGet(
                                () ->
                                        hero
                                                ? designedFallback(item.canonicalTitle())
                                                : coverImage(
                                                        release.primaryCover(),
                                                        item.canonicalTitle())),
                item.logo().map(logo -> logo(logo, item.canonicalTitle())),
                hero ? item.summary() : java.util.Optional.empty());
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
     * The hero never presents a cover as its image: without landscape media it uses the
     * product-owned designed fallback, which the item's own cover may only light.
     */
    private static FeaturedImage designedFallback(String title) {
        return new FeaturedImage.Product("Imagen destacada no disponible de " + title);
    }

    /** A logo stands in for the rendered title, so its alternative text is the title itself. */
    private FeaturedLogo logo(MediaReference logo, String title) {
        ResolvedProviderImage resolved = resolve(logo, Rendition.LOGO);
        return new FeaturedLogo(
                resolved.url(),
                title,
                new CatalogueCover.Attribution(
                        resolved.attributionLabel(), resolved.attributionUrl()));
    }

    private ResolvedProviderImage resolve(MediaReference image, Rendition rendition) {
        return imageResolver.resolve(
                image.provider(), image.reference(), image.sourceUrl(), rendition);
    }
}
