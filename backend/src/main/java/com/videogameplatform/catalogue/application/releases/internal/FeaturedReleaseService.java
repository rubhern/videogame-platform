package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReadMapping;
import com.videogameplatform.catalogue.application.internal.CatalogueReleaseMapping;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.FeaturedPeriodOutOfRangeException;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.Selection;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.Item;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Framework-independent implementation of UC-010. */
public final class FeaturedReleaseService implements BrowseFeaturedReleasesUseCase {

    /** The period's featured release and up to five other featured releases. */
    static final int FEATURED_LIMIT = 6;

    private final FeaturedReleaseReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final FeaturedImageSelection images;
    private final Clock clock;
    private final ReleaseBrowsePolicy browsePolicy;
    private final CatalogueFreshnessPolicy freshnessPolicy;
    private final FeaturedPeriodPolicy periodPolicy;

    public FeaturedReleaseService(
            FeaturedReleaseReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            ProviderImageReferenceResolver imageResolver,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy,
            CatalogueFreshnessPolicy freshnessPolicy,
            FeaturedPeriodPolicy periodPolicy) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.images = new FeaturedImageSelection(imageResolver);
        this.clock = clock;
        this.browsePolicy = browsePolicy;
        this.freshnessPolicy = freshnessPolicy;
        this.periodPolicy = periodPolicy;
    }

    @Override
    public FeaturedReleasesResult browse(Query query) {
        Instant evaluatedAt = clock.instant();
        LocalDate evaluatedOn = LocalDate.ofInstant(evaluatedAt, clock.getZone());
        Granularity granularity = query.granularity();
        LocalDate start =
                query.periodStart().orElseGet(() -> periodPolicy.current(granularity, evaluatedOn));
        // Only a bounded range around the current period is browsed, never arbitrary history.
        if (!periodPolicy.presents(granularity, start, evaluatedOn)) {
            throw new FeaturedPeriodOutOfRangeException(granularity);
        }
        BrowseReleasesResult.Window window = periodPolicy.window(granularity, start);

        FeaturedReleaseReadPort.Result result =
                readPort.findFeaturedReleases(
                                new FeaturedReleaseReadPort.Criteria(
                                        window.from(),
                                        window.to(),
                                        FEATURED_LIMIT,
                                        browsePolicy.releaseGroupLimit(),
                                        normalize(query.platformIds())))
                        .orElseThrow(CatalogueNotReadyException::new);
        if (!result.platformsSupported()) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }

        List<FeaturedReleasesResult.Item> items =
                java.util.stream.IntStream.range(0, result.items().size())
                        .mapToObj(
                                index -> toItem(result.items().get(index), index == 0, evaluatedOn))
                        .toList();
        return new FeaturedReleasesResult(
                granularity,
                evaluatedOn,
                window,
                new FeaturedReleasesResult.Navigation(
                        adjacent(granularity, start, -1, evaluatedOn),
                        adjacent(granularity, start, 1, evaluatedOn)),
                selection(result, evaluatedAt),
                items);
    }

    /** The same deterministic normalization as release exploration: trimmed, distinct, sorted. */
    private static List<String> normalize(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private Optional<LocalDate> adjacent(
            Granularity granularity, LocalDate start, int offset, LocalDate evaluatedOn) {
        return Optional.of(periodPolicy.step(granularity, start, offset))
                .filter(target -> periodPolicy.presents(granularity, target, evaluatedOn));
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

    private FeaturedReleasesResult.Item toItem(Item item, boolean hero, LocalDate evaluatedOn) {
        BrowseReleasesResult.Item release =
                new BrowseReleasesResult.Item(
                        item.gameId(),
                        item.slug(),
                        item.canonicalTitle(),
                        coverPolicy.resolve(item.cover()),
                        item.releases().stream()
                                .map(row -> CatalogueReleaseMapping.map(row, evaluatedOn))
                                .toList(),
                        item.genres());
        return new FeaturedReleasesResult.Item(
                release,
                images.select(
                        hero,
                        item.image(),
                        item.cardImage(),
                        release.primaryCover(),
                        item.canonicalTitle()),
                item.logo().map(logo -> images.logo(logo, item.canonicalTitle())),
                hero ? item.summary() : java.util.Optional.empty());
    }
}
