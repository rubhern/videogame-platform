package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase.Granularity;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

/**
 * The calendar periods featured discovery (UC-010) presents, evaluated at one trusted date in
 * {@code Europe/Madrid}: ISO weeks from Monday to Sunday, and calendar months.
 *
 * <p>Weeks reach {@value #WEEKS_BACK} weeks back and {@value #WEEKS_FORWARD} forward from the
 * current week. Months reach {@value #MONTHS_BACK} months back, across years, and forward to the
 * last calendar month lying entirely inside {@code forwardAcquisitionDays} after the evaluation
 * date: the configured horizon catalogue acquisition is scheduled to cover. That bound states what
 * acquisition is configured to reach, never that the local catalogue is complete. The current
 * period is always presented.
 */
public record FeaturedPeriodPolicy(int forwardAcquisitionDays) {

    static final int WEEKS_BACK = 16;
    static final int WEEKS_FORWARD = 16;
    static final int MONTHS_BACK = 10;

    public FeaturedPeriodPolicy {
        if (forwardAcquisitionDays < 0) {
            throw new IllegalArgumentException("A forward acquisition horizon cannot be negative");
        }
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

    private LocalDate earliest(Granularity granularity, LocalDate today) {
        LocalDate current = current(granularity, today);
        return switch (granularity) {
            case WEEK -> current.minusWeeks(WEEKS_BACK);
            case MONTH -> current.minusMonths(MONTHS_BACK);
        };
    }

    private LocalDate latest(Granularity granularity, LocalDate today) {
        LocalDate current = current(granularity, today);
        return switch (granularity) {
            case WEEK -> current.plusWeeks(WEEKS_FORWARD);
            case MONTH -> {
                // The month after the horizon's last day starts the first month not wholly inside.
                YearMonth lastWhole =
                        YearMonth.from(today.plusDays(forwardAcquisitionDays).plusDays(1))
                                .minusMonths(1);
                LocalDate latestWhole = lastWhole.atDay(1);
                yield latestWhole.isBefore(current) ? current : latestWhole;
            }
        };
    }
}
