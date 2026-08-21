package com.videogameplatform.catalogue.domain;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;

/** A release date that never exposes more precision than the catalogue contains. */
public sealed interface ReleaseDate
        permits ReleaseDate.Day,
                ReleaseDate.Month,
                ReleaseDate.Quarter,
                ReleaseDate.YearOnly,
                ReleaseDate.Unknown {

    Precision precision();

    String value();

    LocalDate periodStart();

    LocalDate periodEnd();

    private static void requireFourDigitYear(int year) {
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("A release date year must be between 1 and 9999");
        }
    }

    record Day(LocalDate date) implements ReleaseDate {
        public Day {
            if (date == null) {
                throw new IllegalArgumentException("A day release date requires a date");
            }
            requireFourDigitYear(date.getYear());
        }

        @Override
        public Precision precision() {
            return Precision.DAY;
        }

        @Override
        public String value() {
            return date.toString();
        }

        @Override
        public LocalDate periodStart() {
            return date;
        }

        @Override
        public LocalDate periodEnd() {
            return date;
        }
    }

    record Month(YearMonth month) implements ReleaseDate {
        public Month {
            if (month == null) {
                throw new IllegalArgumentException("A month release date requires a month");
            }
            requireFourDigitYear(month.getYear());
        }

        @Override
        public Precision precision() {
            return Precision.MONTH;
        }

        @Override
        public String value() {
            return month.toString();
        }

        @Override
        public LocalDate periodStart() {
            return month.atDay(1);
        }

        @Override
        public LocalDate periodEnd() {
            return month.atEndOfMonth();
        }
    }

    record Quarter(int year, int quarter) implements ReleaseDate {
        public Quarter {
            requireFourDigitYear(year);
            if (quarter < 1 || quarter > 4) {
                throw new IllegalArgumentException("A quarter release date is outside its range");
            }
        }

        @Override
        public Precision precision() {
            return Precision.QUARTER;
        }

        @Override
        public String value() {
            return "%04d-Q%d".formatted(year, quarter);
        }

        @Override
        public LocalDate periodStart() {
            return LocalDate.of(year, ((quarter - 1) * 3) + 1, 1);
        }

        @Override
        public LocalDate periodEnd() {
            return periodStart().plusMonths(3).minusDays(1);
        }
    }

    record YearOnly(Year year) implements ReleaseDate {
        public YearOnly {
            if (year == null) {
                throw new IllegalArgumentException("A year release date requires a year");
            }
            requireFourDigitYear(year.getValue());
        }

        @Override
        public Precision precision() {
            return Precision.YEAR;
        }

        @Override
        public String value() {
            return year.toString();
        }

        @Override
        public LocalDate periodStart() {
            return year.atDay(1);
        }

        @Override
        public LocalDate periodEnd() {
            return year.atMonth(12).atEndOfMonth();
        }
    }

    record Unknown() implements ReleaseDate {
        @Override
        public Precision precision() {
            return Precision.UNKNOWN;
        }

        @Override
        public String value() {
            return null;
        }

        @Override
        public LocalDate periodStart() {
            return null;
        }

        @Override
        public LocalDate periodEnd() {
            return null;
        }
    }

    enum Precision {
        DAY("day"),
        MONTH("month"),
        QUARTER("quarter"),
        YEAR("year"),
        UNKNOWN("unknown");

        private final String value;

        Precision(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
