package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReleaseMapping;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Item;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Framework-independent implementation of UC-001. */
public final class ReleaseCatalogueService implements BrowseReleasesUseCase {
    private final ReleaseBrowseReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final Clock clock;
    private final ReleaseBrowsePolicy browsePolicy;
    private final CatalogueFreshnessPolicy freshnessPolicy;

    public ReleaseCatalogueService(
            ReleaseBrowseReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy,
            CatalogueFreshnessPolicy freshnessPolicy) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.clock = clock;
        this.browsePolicy = browsePolicy;
        this.freshnessPolicy = freshnessPolicy;
    }

    @Override
    public BrowseReleasesResult browse(Query query) {
        Instant evaluatedAt = clock.instant();
        LocalDate evaluatedOn = LocalDate.ofInstant(evaluatedAt, clock.getZone());
        BrowseReleasesResult.Window window = window(query.view(), evaluatedOn);
        long offset = Math.multiplyExact((long) query.pageNumber() - 1, query.pageSize());

        Result result =
                readPort.findPublishedReleases(
                                new ReleaseBrowseReadPort.Criteria(
                                        query.view(),
                                        new ReleaseBrowseReadPort.Window(
                                                window.from(), window.to()),
                                        query.platformId(),
                                        query.regionId(),
                                        new ReleaseBrowseReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset),
                                        browsePolicy.includesUnknownUpcomingDates()))
                        .orElseThrow(CatalogueNotReadyException::new);

        validateTaxonomy(query, result);
        long totalPages =
                result.totalItems() / query.pageSize()
                        + (result.totalItems() % query.pageSize() == 0 ? 0 : 1);

        return new BrowseReleasesResult(
                result.publicationVersion(),
                query.view(),
                evaluatedOn,
                window,
                new BrowseReleasesResult.ActiveFilters(query.platformId(), query.regionId()),
                availableFilters(result),
                result.items().stream().map(item -> toItem(item, evaluatedAt)).toList(),
                new BrowseReleasesResult.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }

    private BrowseReleasesResult.Window window(View view, LocalDate evaluatedOn) {
        return switch (view) {
            case RECENT ->
                    new BrowseReleasesResult.Window(
                            evaluatedOn.minusMonths(browsePolicy.recentWindowMonths()),
                            evaluatedOn);
            case UPCOMING ->
                    new BrowseReleasesResult.Window(
                            evaluatedOn,
                            evaluatedOn.plusMonths(browsePolicy.upcomingWindowMonths()));
        };
    }

    private static void validateTaxonomy(Query query, Result result) {
        if (query.platformId() != null
                && result.platforms().stream()
                        .noneMatch(platform -> platform.id().equals(query.platformId()))) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }
        if (query.regionId() != null
                && result.regions().stream()
                        .noneMatch(region -> region.id().equals(query.regionId()))) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.REGION_NOT_SUPPORTED);
        }
    }

    private static BrowseReleasesResult.AvailableFilters availableFilters(Result result) {
        Comparator<ReleaseBrowseReadPort.Taxonomy> order =
                Comparator.comparing(
                                (ReleaseBrowseReadPort.Taxonomy value) ->
                                        value.name().toLowerCase(Locale.ROOT))
                        .thenComparing(ReleaseBrowseReadPort.Taxonomy::id);
        List<BrowseReleasesResult.Taxonomy> platforms =
                result.platforms().stream()
                        .sorted(order)
                        .map(value -> new BrowseReleasesResult.Taxonomy(value.id(), value.name()))
                        .toList();
        List<BrowseReleasesResult.Taxonomy> regions =
                result.regions().stream()
                        .sorted(order)
                        .map(value -> new BrowseReleasesResult.Taxonomy(value.id(), value.name()))
                        .toList();
        return new BrowseReleasesResult.AvailableFilters(platforms, regions);
    }

    private BrowseReleasesResult.Item toItem(Item item, Instant evaluatedAt) {
        BrowseReleasesResult.Release release =
                CatalogueReleaseMapping.map(item, evaluatedAt, freshnessPolicy);
        return new BrowseReleasesResult.Item(
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                coverPolicy.resolve(item.cover()),
                release);
    }
}
