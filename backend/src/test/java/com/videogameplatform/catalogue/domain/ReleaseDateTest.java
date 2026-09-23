package com.videogameplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class ReleaseDateTest {

    @Test
    void preservesEveryApprovedPrecisionWithoutInventingDates() {
        assertThat(new ReleaseDate.Day(LocalDate.of(2026, 8, 13)))
                .extracting(ReleaseDate::precision, ReleaseDate::value)
                .containsExactly(ReleaseDate.Precision.DAY, "2026-08-13");
        assertThat(new ReleaseDate.Month(YearMonth.of(2026, 8)))
                .extracting(ReleaseDate::precision, ReleaseDate::value)
                .containsExactly(ReleaseDate.Precision.MONTH, "2026-08");
        assertThat(new ReleaseDate.Quarter(2026, 3))
                .extracting(
                        ReleaseDate::precision,
                        ReleaseDate::value,
                        ReleaseDate::periodStart,
                        ReleaseDate::periodEnd)
                .containsExactly(
                        ReleaseDate.Precision.QUARTER,
                        "2026-Q3",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 9, 30));
        assertThat(new ReleaseDate.YearOnly(Year.of(2027)))
                .extracting(ReleaseDate::precision, ReleaseDate::value)
                .containsExactly(ReleaseDate.Precision.YEAR, "2027");
        assertThat(new ReleaseDate.Unknown())
                .extracting(
                        ReleaseDate::precision,
                        ReleaseDate::value,
                        ReleaseDate::periodStart,
                        ReleaseDate::periodEnd)
                .containsExactly(ReleaseDate.Precision.UNKNOWN, null, null, null);
    }

    @Test
    void derivesOccurrenceFromPrecisionAndTheTrustedEvaluationDate() {
        // Exact day counts on the day itself.
        ReleaseDate day = new ReleaseDate.Day(LocalDate.of(2026, 9, 20));
        assertThat(day.hasOccurredBy(LocalDate.of(2026, 9, 19))).isFalse();
        assertThat(day.hasOccurredBy(LocalDate.of(2026, 9, 20))).isTrue();
        assertThat(day.hasOccurredBy(LocalDate.of(2026, 9, 21))).isTrue();

        // Month, quarter and year count only once the whole period has ended.
        ReleaseDate month = new ReleaseDate.Month(YearMonth.of(2026, 9));
        assertThat(month.hasOccurredBy(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(month.hasOccurredBy(LocalDate.of(2026, 10, 1))).isTrue();
        ReleaseDate quarter = new ReleaseDate.Quarter(2026, 3);
        assertThat(quarter.hasOccurredBy(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(quarter.hasOccurredBy(LocalDate.of(2026, 10, 1))).isTrue();
        ReleaseDate year = new ReleaseDate.YearOnly(Year.of(2026));
        assertThat(year.hasOccurredBy(LocalDate.of(2026, 12, 31))).isFalse();
        assertThat(year.hasOccurredBy(LocalDate.of(2027, 1, 1))).isTrue();

        // An unknown date can never be decided by time alone.
        assertThat(new ReleaseDate.Unknown().hasOccurredBy(LocalDate.of(2999, 1, 1))).isFalse();
    }

    @Test
    void rejectsYearsThatCannotBeRepresentedByTheApiOrPersistenceContract() {
        assertThatThrownBy(() -> new ReleaseDate.Day(LocalDate.of(10_000, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReleaseDate.Month(YearMonth.of(0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReleaseDate.YearOnly(Year.of(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
