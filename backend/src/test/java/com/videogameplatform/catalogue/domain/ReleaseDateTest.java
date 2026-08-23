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
    void rejectsYearsThatCannotBeRepresentedByTheApiOrPersistenceContract() {
        assertThatThrownBy(() -> new ReleaseDate.Day(LocalDate.of(10_000, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReleaseDate.Month(YearMonth.of(0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReleaseDate.YearOnly(Year.of(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
