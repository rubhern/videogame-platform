package com.videogameplatform.catalogue.application.releases;

import java.time.LocalDate;

/** The moving horizon of Próximos destacados (UC-020), in calendar months from the evaluation date. */
public final class UpcomingHorizon {

    private UpcomingHorizon() {}

    /**
     * The horizon's inclusive last day: the day before the same day of the month {@code months}
     * calendar months later, or that month's last day when the day does not exist in it. Every
     * evaluation date therefore covers at least {@code months} calendar months less a day, and a
     * later date never ends the horizon earlier: with six months, 28, 29, 30 and 31 August 2026
     * end on 27, 28, 28 and 28 February 2027.
     */
    public static LocalDate end(LocalDate evaluatedOn, int months) {
        if (months < 1) {
            throw new IllegalArgumentException("An upcoming horizon spans at least one month");
        }
        LocalDate sameDay = evaluatedOn.plusMonths(months);
        return sameDay.getDayOfMonth() == evaluatedOn.getDayOfMonth()
                ? sameDay.minusDays(1)
                : sameDay;
    }
}
