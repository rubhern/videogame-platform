package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase.Granularity;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.settings.ProductSetting;
import com.videogameplatform.catalogue.application.settings.ProductSettings;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

/**
 * The calendar periods featured discovery (UC-010) presents, evaluated at one trusted date in
 * {@code Europe/Madrid}: ISO weeks from Monday to Sunday, and calendar months.
 *
 * <p>Weeks reach {@code weeksBack} weeks back and {@code weeksForward} forward from the current
 * week; months reach {@code monthsBack} months back, across years, and {@code monthsForward}
 * forward. The four bounds are operator product settings (#450); the current period is always
 * presented. Months forward are no longer clipped to the configured acquisition horizon: the
 * operator panel warns when they reach beyond it, because nothing fills those months
 * automatically.
 */
public record FeaturedPeriodPolicy(
        int weeksBack, int weeksForward, int monthsBack, int monthsForward) {

    public FeaturedPeriodPolicy {
        if (weeksBack < 0 || weeksForward < 0 || monthsBack < 0 || monthsForward < 0) {
            throw new IllegalArgumentException("A featured period bound cannot be negative");
        }
    }

    /** The bounds of one settings snapshot. */
    public static FeaturedPeriodPolicy of(ProductSettings settings) {
        return new FeaturedPeriodPolicy(
                settings.integer(ProductSetting.FEATURED_WEEKS_BACK),
                settings.integer(ProductSetting.FEATURED_WEEKS_FORWARD),
                settings.integer(ProductSetting.FEATURED_MONTHS_BACK),
                settings.integer(ProductSetting.FEATURED_MONTHS_FORWARD));
    }

    /** The first day of the period holding {@code today}. */
    LocalDate current(Granularity granularity, LocalDate today) {
        return switch (granularity) {
            case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> today.withDayOfMonth(1);
        };
    }

    /** Whether the period starting on {@code start} may be presented at {@code today}. */
    boolean presents(Granularity granularity, LocalDate start, LocalDate today) {
        return !start.isBefore(earliest(granularity, today))
                && !start.isAfter(latest(granularity, today));
    }

    /** The adjacent period's first day, {@code offset} periods away. */
    LocalDate step(Granularity granularity, LocalDate start, int offset) {
        return switch (granularity) {
            case WEEK -> start.plusWeeks(offset);
            case MONTH -> start.plusMonths(offset);
        };
    }

    /** The period's first and last day, inclusive. */
    BrowseReleasesResult.Window window(Granularity granularity, LocalDate start) {
        return switch (granularity) {
            case WEEK -> new BrowseReleasesResult.Window(start, start.plusDays(6));
            case MONTH ->
                    new BrowseReleasesResult.Window(start, YearMonth.from(start).atEndOfMonth());
        };
    }

    /**
     * The last month the forward bound presents at {@code today}, never before the current one.
     * The release calendar (UC-016) shares it, so both monthly views reach the same future.
     */
    YearMonth lastMonth(LocalDate today) {
        return YearMonth.from(latest(Granularity.MONTH, today));
    }

    private LocalDate earliest(Granularity granularity, LocalDate today) {
        LocalDate current = current(granularity, today);
        return switch (granularity) {
            case WEEK -> current.minusWeeks(weeksBack);
            case MONTH -> current.minusMonths(monthsBack);
        };
    }

    private LocalDate latest(Granularity granularity, LocalDate today) {
        LocalDate current = current(granularity, today);
        return switch (granularity) {
            case WEEK -> current.plusWeeks(weeksForward);
            case MONTH -> current.plusMonths(monthsForward);
        };
    }
}
