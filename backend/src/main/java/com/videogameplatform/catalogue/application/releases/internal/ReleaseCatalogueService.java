package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReleaseMapping;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Item;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Result;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** Framework-independent implementation of UC-001. */
public final class ReleaseCatalogueService implements BrowseReleasesUseCase {
    private final ReleaseBrowseReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final Clock clock;
    private final ReleaseBrowsePolicy browsePolicy;

    public ReleaseCatalogueService(
            ReleaseBrowseReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.clock = clock;
        this.browsePolicy = browsePolicy;
    }

    @Override
    public BrowseReleasesResult browse(Query query) {
        LocalDate evaluatedOn = LocalDate.ofInstant(clock.instant(), clock.getZone());
        BrowseReleasesResult.Window window = window(query, evaluatedOn);
        long offset = Math.multiplyExact((long) query.pageNumber() - 1, query.pageSize());

        List<String> platformIds = ReleaseFilters.normalize(query.platformIds());
        List<String> regionIds = ReleaseFilters.normalize(query.regionIds());

        Result result =
                readPort.findPublishedReleases(
                                new ReleaseBrowseReadPort.Criteria(
                                        new ReleaseBrowseReadPort.Window(
                                                window.from(), window.to()),
                                        evaluatedOn,
                                        query.sort(),
                                        platformIds,
                                        regionIds,
                                        new ReleaseBrowseReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset),
                                        // Approximate dates only ever join from today on, so a
                                        // range that ends earlier has nothing for them to admit.
                                        query.includeApproximateDates()
                                                && !window.to().isBefore(evaluatedOn),
                                        browsePolicy.releaseGroupLimit()))
                        .orElseThrow(CatalogueNotReadyException::new);

        ReleaseFilters.validate(platformIds, regionIds, result.platforms(), result.regions());
        long totalPages =
                result.totalItems() / query.pageSize()
                        + (result.totalItems() % query.pageSize() == 0 ? 0 : 1);

        return new BrowseReleasesResult(
                result.publicationVersion(),
                evaluatedOn,
                window,
                new BrowseReleasesResult.ActiveFilters(platformIds, regionIds),
                ReleaseFilters.available(result.platforms(), result.regions()),
                result.items().stream().map(item -> toItem(item, evaluatedOn)).toList(),
                new BrowseReleasesResult.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }

    /**
     * The requested inclusive range, or the current calendar week (Monday to Sunday) of the trusted
     * evaluation date when the request names none. Calendar arithmetic alone validates a range; no
     * range depends on the browser's notion of today.
     */
    private static BrowseReleasesResult.Window window(Query query, LocalDate evaluatedOn) {
        if (query.from() == null && query.to() == null) {
            LocalDate monday = evaluatedOn.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new BrowseReleasesResult.Window(monday, monday.plusDays(6));
        }
        if (query.from() == null) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.RANGE_START_MISSING);
        }
        if (query.to() == null) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.RANGE_END_MISSING);
        }
        if (query.to().isBefore(query.from())
                || query.from().getYear() < 1
                || query.to().getYear() > 9999
                || ChronoUnit.DAYS.between(query.from(), query.to()) + 1 > MAX_RANGE_DAYS) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.RANGE_INVALID);
        }
        return new BrowseReleasesResult.Window(query.from(), query.to());
    }

    private BrowseReleasesResult.Item toItem(Item item, LocalDate evaluatedOn) {
        List<BrowseReleasesResult.Release> releases =
                item.releases().stream()
                        .map((ReleaseRow row) -> CatalogueReleaseMapping.map(row, evaluatedOn))
                        .toList();
        return new BrowseReleasesResult.Item(
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                coverPolicy.resolve(item.cover()),
                releases,
                item.genres());
    }
}
