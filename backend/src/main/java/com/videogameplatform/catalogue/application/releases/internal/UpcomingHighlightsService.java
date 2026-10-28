package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReadMapping;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.BrowseUpcomingHighlightsUseCase;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.Selection;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.UpcomingHighlightsResult;
import com.videogameplatform.catalogue.application.releases.port.UpcomingHighlightsReadPort;
import com.videogameplatform.catalogue.domain.EffectiveReleaseStatusPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Framework-independent implementation of UC-020 (Próximos destacados). */
public final class UpcomingHighlightsService implements BrowseUpcomingHighlightsUseCase {

    /** The most anticipated games a platform shows (owner-approved product limit, #388). */
    static final int UPCOMING_HIGHLIGHTS_LIMIT = 10;

    /** The moving horizon, in calendar months from the evaluation date. */
    static final int HORIZON_MONTHS = 6;

    private final UpcomingHighlightsReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final FeaturedImageSelection images;
    private final Clock clock;
    private final CatalogueFreshnessPolicy freshnessPolicy;

    public UpcomingHighlightsService(
            UpcomingHighlightsReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            ProviderImageReferenceResolver imageResolver,
            Clock clock,
            CatalogueFreshnessPolicy freshnessPolicy) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.images = new FeaturedImageSelection(imageResolver);
        this.clock = clock;
        this.freshnessPolicy = freshnessPolicy;
    }

    @Override
    public UpcomingHighlightsResult browse(Query query) {
        if (query.platformIds().isEmpty()) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }
        Instant evaluatedAt = clock.instant();
        LocalDate evaluatedOn = LocalDate.ofInstant(evaluatedAt, clock.getZone());
        LocalDate horizonEnd = horizonEnd(evaluatedOn);
        List<String> platformIds = query.platformIds().stream().distinct().sorted().toList();

        UpcomingHighlightsReadPort.Result result =
                readPort.findUpcomingHighlights(
                                new UpcomingHighlightsReadPort.Criteria(
                                        platformIds,
                                        evaluatedOn,
                                        horizonEnd,
                                        UPCOMING_HIGHLIGHTS_LIMIT))
                        .orElseThrow(CatalogueNotReadyException::new);
        if (result.platforms().size() != platformIds.size()) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }

        List<UpcomingHighlightsResult.Item> items =
                IntStream.range(0, result.items().size())
                        .mapToObj(
                                index -> toItem(index + 1, result.items().get(index), evaluatedOn))
                        .toList();
        return new UpcomingHighlightsResult(
                result.platforms(),
                evaluatedOn,
                new BrowseReleasesResult.Window(evaluatedOn, horizonEnd),
                selection(result, evaluatedAt),
                items);
    }

    /**
     * The horizon's inclusive last day: the day before the same day of the month six calendar
     * months later, or that month's last day when the day does not exist in it. Every evaluation
     * date therefore covers at least six calendar months less a day, and a later date never ends
     * the horizon earlier: 28, 29, 30 and 31 August 2026 end on 27, 28, 28 and 28 February 2027.
     */
    static LocalDate horizonEnd(LocalDate evaluatedOn) {
        LocalDate sameDay = evaluatedOn.plusMonths(HORIZON_MONTHS);
        return sameDay.getDayOfMonth() == evaluatedOn.getDayOfMonth()
                ? sameDay.minusDays(1)
                : sameDay;
    }

    /** The oldest signal among the ranked games bounds how current the whole ranking is. */
    private Selection selection(UpcomingHighlightsReadPort.Result result, Instant evaluatedAt) {
        if (result.items().isEmpty()) {
            return result.candidates()
                    ? new Selection.PopularityUnavailable()
                    : new Selection.NoQualifyingReleases();
        }
        Instant oldest =
                result.items().stream()
                        .map(UpcomingHighlightsReadPort.Item::popularityObservedAt)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new Selection.Ranked(
                CatalogueReadMapping.toFreshness(freshnessPolicy.status(oldest, evaluatedAt)),
                oldest);
    }

    private UpcomingHighlightsResult.Item toItem(
            int position, UpcomingHighlightsReadPort.Item item, LocalDate evaluatedOn) {
        CatalogueCover cover = coverPolicy.resolve(item.cover());
        return new UpcomingHighlightsResult.Item(
                position,
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                cover,
                images.select(
                        position == 1,
                        item.image(),
                        item.cardImage(),
                        cover,
                        item.canonicalTitle()),
                item.genres(),
                new UpcomingHighlightsResult.PlatformRelease(
                        item.platform(),
                        item.region(),
                        CatalogueReadMapping.toReleaseDate(item.releaseDate()),
                        CatalogueReadMapping.toStatus(
                                EffectiveReleaseStatusPolicy.effectiveStatus(
                                        item.status(), item.releaseDate(), evaluatedOn))));
    }
}
