package com.videogameplatform.api.delivery.catalogue.release;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.IsoFields;
import java.util.Optional;

/**
 * The contract's ISO 8601 week, {@code YYYY-Www}, as the Monday that starts it. {@code YYYY} is the
 * week-based year, so a week may start in the previous calendar year or end in the next one.
 */
final class IsoWeekParameter {

    private IsoWeekParameter() {}

    /**
     * The Monday of a week the contract pattern already shaped, or empty when the year has no such
     * week: week 53 exists only in a year that has it.
     */
    static Optional<LocalDate> monday(String week) {
        int year = Integer.parseInt(week.substring(0, 4));
        int number = Integer.parseInt(week.substring(6));
        // 28 December always falls in the last ISO week of its week-based year.
        int weeksInYear =
                LocalDate.of(year, Month.DECEMBER, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        if (number < 1 || number > weeksInYear) {
            return Optional.empty();
        }
        return Optional.of(
                LocalDate.of(year, Month.JANUARY, 4)
                        .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, number)
                        .with(DayOfWeek.MONDAY));
    }

    /** The week holding {@code date}, as {@code YYYY-Www}. */
    static String format(LocalDate date) {
        return "%04d-W%02d"
                .formatted(
                        date.get(IsoFields.WEEK_BASED_YEAR),
                        date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
