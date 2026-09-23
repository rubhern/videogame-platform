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
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
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
        BrowseReleasesResult.Window window = window(query.view(), query.weeks(), evaluatedOn);
        long offset = Math.multiplyExact((long) query.pageNumber() - 1, query.pageSize());

        // Deterministic normalization for multi-select filters: trim, drop blanks, de-duplicate and
        // sort, so repeated values collapse and the applied filter is stable and reproducible.
        List<String> platformIds = normalize(query.platformIds());
        List<String> regionIds = normalize(query.regionIds());

        Result result =
                readPort.findPublishedReleases(
                                new ReleaseBrowseReadPort.Criteria(
                                        query.view(),
                                        new ReleaseBrowseReadPort.Window(
                                                window.from(), window.to()),
                                        platformIds,
                                        regionIds,
                                        new ReleaseBrowseReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset),
                                        browsePolicy.includesUnknownUpcomingDates(),
                                        browsePolicy.releaseGroupLimit()))
                        .orElseThrow(CatalogueNotReadyException::new);

        validateTaxonomy(platformIds, regionIds, result);
        long totalPages =
                result.totalItems() / query.pageSize()
                        + (result.totalItems() % query.pageSize() == 0 ? 0 : 1);

        return new BrowseReleasesResult(
                result.publicationVersion(),
                query.view(),
                evaluatedOn,
                window,
                new BrowseReleasesResult.ActiveFilters(platformIds, regionIds),
                availableFilters(result),
                result.items().stream()
                        .map(item -> toItem(item, evaluatedAt, evaluatedOn))
                        .toList(),
                new BrowseReleasesResult.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }

    private static List<String> normalize(List<String> values) {
        return values.stream()
                .filter(value -> value != null)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private BrowseReleasesResult.Window window(View view, int weeks, LocalDate evaluatedOn) {
        return switch (view) {
            case RECENT ->
                    new BrowseReleasesResult.Window(
                            evaluatedOn.minusDays(weeks * 7L - 1), evaluatedOn);
            case UPCOMING ->
                    new BrowseReleasesResult.Window(evaluatedOn, evaluatedOn.plusWeeks(weeks));
        };
    }

    private static void validateTaxonomy(
            List<String> platformIds, List<String> regionIds, Result result) {
        // A selected value is valid only if it is a real taxonomy id, which the read port keeps
        // representable in the returned facets. Any unknown id fails the whole request.
        if (!platformIds.stream()
                .allMatch(
                        requested ->
                                result.platforms().stream()
                                        .anyMatch(platform -> platform.id().equals(requested)))) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }
        if (!regionIds.stream()
                .allMatch(
                        requested ->
                                result.regions().stream()
                                        .anyMatch(region -> region.id().equals(requested)))) {
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

    private BrowseReleasesResult.Item toItem(
            Item item, Instant evaluatedAt, LocalDate evaluatedOn) {
        List<BrowseReleasesResult.Release> releases =
                item.releases().stream()
                        .map(
                                (ReleaseRow row) ->
                                        CatalogueReleaseMapping.map(
                                                row, evaluatedAt, evaluatedOn, freshnessPolicy))
                        .toList();
        return new BrowseReleasesResult.Item(
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                coverPolicy.resolve(item.cover()),
                releases);
    }
}
