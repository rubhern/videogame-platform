package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.releases.BrowseReleaseCalendarUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.ReleaseCalendarResult;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Framework-independent implementation of UC-016. The application alone knows today in {@code
 * Europe/Madrid}: it decides the current month, rejects any earlier month and any month beyond the
 * forward bound featured months share, and counts the month only from that date on: a day that has
 * gone by is never part of the calendar.
 */
public final class ReleaseCalendarService implements BrowseReleaseCalendarUseCase {

    private final ReleaseCalendarReadPort readPort;
    private final Clock clock;
    private final FeaturedPeriodPolicy periodPolicy;

    public ReleaseCalendarService(
            ReleaseCalendarReadPort readPort, Clock clock, FeaturedPeriodPolicy periodPolicy) {
        this.readPort = readPort;
        this.clock = clock;
        this.periodPolicy = periodPolicy;
    }

    @Override
    public ReleaseCalendarResult browse(Query query) {
        LocalDate evaluatedOn = LocalDate.ofInstant(clock.instant(), clock.getZone());
        YearMonth first = YearMonth.from(evaluatedOn);
        YearMonth last = periodPolicy.lastMonth(evaluatedOn);
        YearMonth month = query.month().orElse(first);
        if (month.isBefore(first) || month.isAfter(last)) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.MONTH_OUT_OF_RANGE);
        }

        List<String> platformIds = ReleaseFilters.normalize(query.platformIds());
        List<String> regionIds = ReleaseFilters.normalize(query.regionIds());
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        // The current month is counted from today; a later month starts on its first day.
        LocalDate countedFrom = from.isBefore(evaluatedOn) ? evaluatedOn : from;
        ReleaseCalendarReadPort.Result result =
                readPort.countPublishedReleases(
                                new ReleaseCalendarReadPort.Criteria(
                                        countedFrom, to, evaluatedOn, platformIds, regionIds))
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
                        .toList());
    }
}
