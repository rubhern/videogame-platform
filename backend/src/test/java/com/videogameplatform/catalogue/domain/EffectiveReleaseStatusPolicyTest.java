package com.videogameplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * The effective status is derived from persisted evidence and the trusted evaluation date, so it
 * never depends on when the last synchronization ran.
 */
class EffectiveReleaseStatusPolicyTest {

    private static final LocalDate EVALUATION_DATE = LocalDate.of(2026, 9, 6);

    @Test
    void keepsProviderEvidenceThatTheClockCannotDecide() {
        assertThat(effective(ReleaseStatus.CANCELLED, new ReleaseDate.Day(EVALUATION_DATE)))
                .isEqualTo(ReleaseStatus.CANCELLED);
        assertThat(effective(ReleaseStatus.DELAYED, new ReleaseDate.Day(EVALUATION_DATE)))
                .isEqualTo(ReleaseStatus.DELAYED);
        assertThat(effective(ReleaseStatus.RELEASED, new ReleaseDate.Unknown()))
                .isEqualTo(ReleaseStatus.RELEASED);
    }

    @Test
    void countsAnExactDayOnTheDayItself() {
        assertThat(announced(new ReleaseDate.Day(EVALUATION_DATE)))
                .isEqualTo(ReleaseStatus.RELEASED);
        assertThat(announced(new ReleaseDate.Day(EVALUATION_DATE.plusDays(1))))
                .isEqualTo(ReleaseStatus.SCHEDULED);
    }

    @Test
    void countsAPeriodOnlyOnceItHasEnded() {
        assertThat(announced(new ReleaseDate.Month(YearMonth.of(2026, 8))))
                .isEqualTo(ReleaseStatus.RELEASED);
        assertThat(announced(new ReleaseDate.Month(YearMonth.of(2026, 9))))
                .isEqualTo(ReleaseStatus.SCHEDULED);
        assertThat(announced(new ReleaseDate.Quarter(2026, 2))).isEqualTo(ReleaseStatus.RELEASED);
        assertThat(announced(new ReleaseDate.Quarter(2026, 3))).isEqualTo(ReleaseStatus.SCHEDULED);
        assertThat(announced(new ReleaseDate.YearOnly(Year.of(2025))))
                .isEqualTo(ReleaseStatus.RELEASED);
        assertThat(announced(new ReleaseDate.YearOnly(Year.of(2026))))
                .isEqualTo(ReleaseStatus.SCHEDULED);
    }

    @Test
    void keepsAnUnknownAnnouncedDateAnnounced() {
        assertThat(announced(new ReleaseDate.Unknown())).isEqualTo(ReleaseStatus.ANNOUNCED);
    }

    @Test
    void crossesFromScheduledToReleasedPurelyBecauseTimeAdvanced() {
        // The persisted evidence never changes: a provider release with no negative signal and a
        // fixed known date. Only the trusted evaluation date moves.
        ReleaseDate date = new ReleaseDate.Day(LocalDate.of(2026, 9, 20));
        assertThat(
                        EffectiveReleaseStatusPolicy.effectiveStatus(
                                ReleaseStatus.ANNOUNCED, date, LocalDate.of(2026, 9, 19)))
                .isEqualTo(ReleaseStatus.SCHEDULED);
        assertThat(
                        EffectiveReleaseStatusPolicy.effectiveStatus(
                                ReleaseStatus.ANNOUNCED, date, LocalDate.of(2026, 9, 20)))
                .isEqualTo(ReleaseStatus.RELEASED);
        assertThat(
                        EffectiveReleaseStatusPolicy.effectiveStatus(
                                ReleaseStatus.ANNOUNCED, date, LocalDate.of(2026, 9, 21)))
                .isEqualTo(ReleaseStatus.RELEASED);
    }

    private static ReleaseStatus announced(ReleaseDate date) {
        return effective(ReleaseStatus.ANNOUNCED, date);
    }

    private static ReleaseStatus effective(ReleaseStatus persisted, ReleaseDate date) {
        return EffectiveReleaseStatusPolicy.effectiveStatus(persisted, date, EVALUATION_DATE);
    }
}
