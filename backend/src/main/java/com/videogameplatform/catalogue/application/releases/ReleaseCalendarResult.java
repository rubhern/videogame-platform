package com.videogameplatform.catalogue.application.releases;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Provider-independent per-day game counts of one calendar month, from the evaluation date on.
 * Days hold exact-day releases only; a month, quarter, year or unknown date is never counted.
 */
public record ReleaseCalendarResult(
        YearMonth month,
        LocalDate evaluatedOn,
        BrowseReleasesResult.Window window,
        Navigation navigation,
        BrowseReleasesResult.ActiveFilters activeFilters,
        BrowseReleasesResult.AvailableFilters availableFilters,
        List<Day> days) {

    public ReleaseCalendarResult {
        days = List.copyOf(days);
    }

    /** The requestable months and the neighbours of this one; a neighbour is empty at a bound. */
    public record Navigation(
            YearMonth first,
            YearMonth last,
            Optional<YearMonth> previous,
            Optional<YearMonth> next) {}

    /** A day of the month and the games with at least one qualifying release on it. */
    public record Day(LocalDate date, long gameCount, long totalHype) {}
}
