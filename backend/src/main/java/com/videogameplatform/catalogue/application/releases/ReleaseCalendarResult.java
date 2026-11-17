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
        List<Day> days,
        Presentation presentation) {

    public ReleaseCalendarResult {
        days = List.copyOf(days);
    }

    /**
     * How the month is presented, from the operator's product settings (#450): the four ascending
     * daily total-Hype thresholds from low to very high interest, and how many games a chosen day
     * previews.
     */
    public record Presentation(List<Long> interestThresholds, int dayPreviewSize) {
        public Presentation {
            interestThresholds = List.copyOf(interestThresholds);
        }
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
