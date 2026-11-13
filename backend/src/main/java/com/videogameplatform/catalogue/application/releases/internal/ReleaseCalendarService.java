package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.releases.BrowseReleaseCalendarUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.ReleaseCalendarResult;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort;
import com.videogameplatform.catalogue.application.settings.CurrentProductSettings;
import com.videogameplatform.catalogue.application.settings.ProductSetting;
import com.videogameplatform.catalogue.application.settings.ProductSettings;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Framework-independent implementation of UC-016. The dedicated calendar starts today in Madrid.
 * The home timeline reads full months across the featured navigation bounds. Both use one product
 * settings snapshot.
 */
public final class ReleaseCalendarService implements BrowseReleaseCalendarUseCase {

    private final ReleaseCalendarReadPort readPort;
    private final Clock clock;
    private final CurrentProductSettings settings;

    public ReleaseCalendarService(
            ReleaseCalendarReadPort readPort, Clock clock, CurrentProductSettings settings) {
        this.readPort = readPort;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public ReleaseCalendarResult browse(Query query) {
        ProductSettings current = settings.current();
        FeaturedPeriodPolicy periodPolicy = FeaturedPeriodPolicy.of(current);
        LocalDate evaluatedOn = LocalDate.ofInstant(clock.instant(), clock.getZone());
        YearMonth currentMonth = YearMonth.from(evaluatedOn);
        YearMonth first =
                query.includePast() ? periodPolicy.firstTimelineMonth(evaluatedOn) : currentMonth;
        YearMonth last =
                query.includePast()
                        ? periodPolicy.lastTimelineMonth(evaluatedOn)
                        : periodPolicy.lastMonth(evaluatedOn);
        YearMonth month = query.month().orElse(currentMonth);
        if (month.isBefore(first) || month.isAfter(last)) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.MONTH_OUT_OF_RANGE);
        }

        List<String> platformIds = ReleaseFilters.normalize(query.platformIds());
        List<String> regionIds = ReleaseFilters.normalize(query.regionIds());
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        LocalDate countedFrom =
                !query.includePast() && from.isBefore(evaluatedOn) ? evaluatedOn : from;
        ReleaseCalendarReadPort.Result result =
                readPort.countPublishedReleases(
                                new ReleaseCalendarReadPort.Criteria(
                                        countedFrom,
                                        to,
                                        evaluatedOn,
                                        platformIds,
                                        regionIds,
                                        current.calendarMinimumHypes()))
                        .orElseThrow(CatalogueNotReadyException::new);
        ReleaseFilters.validate(platformIds, regionIds, result.platforms(), result.regions());

        return new ReleaseCalendarResult(
                month,
                evaluatedOn,
                new BrowseReleasesResult.Window(from, to),
                new ReleaseCalendarResult.Navigation(
                        first,
                        last,
                        month.equals(first) ? Optional.empty() : Optional.of(month.minusMonths(1)),
                        month.equals(last) ? Optional.empty() : Optional.of(month.plusMonths(1))),
                new BrowseReleasesResult.ActiveFilters(platformIds, regionIds),
                ReleaseFilters.available(result.platforms(), result.regions()),
                result.days().stream()
                        .map(
                                day ->
                                        new ReleaseCalendarResult.Day(
                                                day.date(), day.gameCount(), day.totalHype()))
                        .toList(),
                new ReleaseCalendarResult.Presentation(
                        current.interestBands(),
                        current.integer(ProductSetting.CALENDAR_DAY_PREVIEW_SIZE)));
    }
}
