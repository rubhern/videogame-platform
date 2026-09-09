package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** The provider decides cancellation and delay; the application decides what has happened. */
class ReleaseStatusPolicyTest {

    private static final LocalDate EVALUATION_DATE = LocalDate.of(2026, 9, 6);

    @Test
    void trustsTheProviderOnlyForCancellationAndDelay() {
        assertThat(
                        ReleaseStatusPolicy.derive(
                                ProviderReleaseSignal.CANCELLED,
                                new ReleaseDate.Day(LocalDate.of(2020, 1, 1)),
                                EVALUATION_DATE))
                .isEqualTo(ReleaseStatus.CANCELLED);
        assertThat(
                        ReleaseStatusPolicy.derive(
                                ProviderReleaseSignal.DELAYED,
                                new ReleaseDate.Day(LocalDate.of(2020, 1, 1)),
                                EVALUATION_DATE))
                .isEqualTo(ReleaseStatus.DELAYED);
    }

    @Test
    void countsAnExactDayOnTheDayItself() {
        assertThat(derive(new ReleaseDate.Day(EVALUATION_DATE))).isEqualTo(ReleaseStatus.RELEASED);
        assertThat(derive(new ReleaseDate.Day(EVALUATION_DATE.plusDays(1))))
                .isEqualTo(ReleaseStatus.SCHEDULED);
    }

    @Test
    void countsAPeriodOnlyOnceItHasEnded() {
        assertThat(derive(new ReleaseDate.Month(YearMonth.of(2026, 8))))
                .isEqualTo(ReleaseStatus.RELEASED);
        assertThat(derive(new ReleaseDate.Month(YearMonth.of(2026, 9))))
                .isEqualTo(ReleaseStatus.SCHEDULED);
        assertThat(derive(new ReleaseDate.Quarter(2026, 2))).isEqualTo(ReleaseStatus.RELEASED);
        assertThat(derive(new ReleaseDate.Quarter(2026, 3))).isEqualTo(ReleaseStatus.SCHEDULED);
        assertThat(derive(new ReleaseDate.YearOnly(Year.of(2025))))
                .isEqualTo(ReleaseStatus.RELEASED);
        assertThat(derive(new ReleaseDate.YearOnly(Year.of(2026))))
                .isEqualTo(ReleaseStatus.SCHEDULED);
    }

    @Test
    void keepsAnUnknownDateAnnouncedRatherThanInventingAnOccurrence() {
        assertThat(derive(new ReleaseDate.Unknown())).isEqualTo(ReleaseStatus.ANNOUNCED);
    }

    private static ReleaseStatus derive(ReleaseDate date) {
        return ReleaseStatusPolicy.derive(ProviderReleaseSignal.NONE, date, EVALUATION_DATE);
    }
}
