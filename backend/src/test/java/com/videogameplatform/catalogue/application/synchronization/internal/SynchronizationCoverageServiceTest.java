package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverage.LastScan;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverage.Period;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CoveredInterval;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SynchronizationCoverageServiceTest {
    private static final LastScan FIRST =
            new LastScan(
                    UUID.fromString("10000000-0000-4000-8000-000000000001"),
                    Instant.parse("2026-10-01T04:00:00Z"),
                    Instant.parse("2026-10-01T04:30:00Z"),
                    0);
    private static final LastScan SECOND =
            new LastScan(
                    UUID.fromString("10000000-0000-4000-8000-000000000002"),
                    Instant.parse("2026-10-02T04:00:00Z"),
                    Instant.parse("2026-10-02T04:10:00Z"),
                    3);

    private final CatalogueSynchronizationStore store = mock(CatalogueSynchronizationStore.class);
    private final SynchronizationCoverageService service =
            new SynchronizationCoverageService(store);

    @Test
    void neverCoveredRangeIsOneUncoveredPeriod() {
        stored("1997-12-01", "1997-12-31", List.of());

        var coverage = service.coverage(day("1997-12-01"), day("1997-12-31"));

        assertThat(coverage.periods())
                .containsExactly(
                        new Period(day("1997-12-01"), day("1997-12-31"), Optional.empty()));
        assertThat(coverage.continuesFrom()).isEmpty();
    }

    @Test
    void clampsIntervalsToTheRangeAndFillsEveryGapIncludingAdjacentSuccesses() {
        stored(
                "1997-01-10",
                "1997-12-20",
                List.of(
                        interval("1996-12-01", "1997-01-31", FIRST),
                        interval("1997-02-01", "1997-02-28", SECOND),
                        interval("1997-06-01", "1998-06-30", FIRST)));

        var coverage = service.coverage(day("1997-01-10"), day("1997-12-20"));

        assertThat(coverage.periods())
                .containsExactly(
                        new Period(day("1997-01-10"), day("1997-01-31"), Optional.of(FIRST)),
                        new Period(day("1997-02-01"), day("1997-02-28"), Optional.of(SECOND)),
                        new Period(day("1997-03-01"), day("1997-05-31"), Optional.empty()),
                        new Period(day("1997-06-01"), day("1997-12-20"), Optional.of(FIRST)));
        assertThat(coverage.continuesFrom()).isEmpty();
    }

    @Test
    void boundsAnAnswerAndContinuesFromTheFirstUnansweredDay() {
        int bound = SynchronizationCoverageService.MAX_COVERED_INTERVALS;
        List<CoveredInterval> everyOtherDay = new ArrayList<>();
        for (int day = 0; day <= bound; day++) {
            LocalDate covered = day("2000-01-02").plusDays(2L * day);
            everyOtherDay.add(new CoveredInterval(covered, covered, FIRST));
        }
        stored("2000-01-01", "2010-12-31", everyOtherDay);

        var coverage = service.coverage(day("2000-01-01"), day("2010-12-31"));

        LocalDate lastAnswered = day("2000-01-02").plusDays(2L * (bound - 1));
        assertThat(coverage.periods()).hasSize(2 * bound);
        assertThat(coverage.periods().getLast())
                .isEqualTo(new Period(lastAnswered, lastAnswered, Optional.of(FIRST)));
        assertThat(coverage.continuesFrom()).contains(lastAnswered.plusDays(1));
    }

    @Test
    void rejectsAnInvertedOrMissingRangeWithoutReading() {
        assertThatThrownBy(() -> service.coverage(day("1998-01-01"), day("1997-12-31")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.coverage(null, day("1997-12-31")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    private void stored(String from, String to, List<CoveredInterval> intervals) {
        when(store.coveredIntervals(
                        day(from),
                        day(to),
                        SynchronizationCoverageService.MAX_COVERED_INTERVALS + 1))
                .thenReturn(intervals);
    }

    private static CoveredInterval interval(String from, String to, LastScan success) {
        return new CoveredInterval(day(from), day(to), success);
    }

    private static LocalDate day(String value) {
        return LocalDate.parse(value);
    }
}
