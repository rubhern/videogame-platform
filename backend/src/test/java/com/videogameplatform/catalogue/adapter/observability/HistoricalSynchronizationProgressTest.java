package com.videogameplatform.catalogue.adapter.observability;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.HistoricalBackfillStore;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoricalSynchronizationProgressTest {
    @Test
    void snapshotsAreThrottledAndCompletionIsAlwaysPersistedWithoutChangingTheRun() {
        var store = mock(HistoricalBackfillStore.class);
        var delegate = mock(SynchronizationProgress.class);
        var log = mock(SynchronizationProgress.Run.class);
        when(delegate.started(any(), any(), any(), anyInt())).thenReturn(log);
        var now = Instant.parse("2026-10-23T08:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var id = UUID.randomUUID();
        var request =
                new CatalogueSynchronizationRequest(
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));
        var progress =
                new HistoricalSynchronizationProgress(delegate, store, clock)
                        .started(id, request, now, 10);
        var counters = mock(CatalogueSynchronizationReport.Counters.class);
        var report =
                new CatalogueSynchronizationReport(
                        id,
                        request.from(),
                        request.to(),
                        now,
                        now,
                        SynchronizationOutcome.SUCCEEDED,
                        "SYNCHRONIZATION_COMPLETED",
                        counters);
        progress.pageRequested(1, counters);
        progress.gameSucceeded(1, 1, SynchronizationProgress.GameResult.CREATED, counters);
        progress.finished(report);
        verify(store, times(2)).progress(id, counters, now);
        verify(log).finished(report);
        doThrow(new IllegalStateException("test fixture")).when(store).progress(id, counters, now);
        assertThatNoException().isThrownBy(() -> progress.finished(report));
    }
}
