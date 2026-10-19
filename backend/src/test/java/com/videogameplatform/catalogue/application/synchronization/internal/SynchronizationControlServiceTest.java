package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.application.administration.AbandonmentOutcome;
import com.videogameplatform.catalogue.application.administration.StopRequestOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SynchronizationControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final UUID RUN = UUID.fromString("10000000-0000-4000-8000-000000000001");

    private final CatalogueSynchronizationStore store = mock(CatalogueSynchronizationStore.class);
    private final SynchronizationControlService service =
            new SynchronizationControlService(
                    store,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new SynchronizationPolicy(500, 25, 50, Duration.ofMinutes(30)));

    @Test
    void recordsTheStopOfTheNamedRunAtTheTrustedClockAndReturnsTheStoreAnswer() {
        var requested = new StopRequestOutcome.Requested(RUN, NOW.minusSeconds(5));
        when(store.requestStop(RUN, NOW)).thenReturn(requested);

        assertThat(service.requestStop(RUN)).isSameAs(requested);
    }

    @Test
    void judgesStalenessWithTheSameAbandonmentPeriodThatReportsARunStalled() {
        var notStale = new AbandonmentOutcome.NotStale(NOW.minusSeconds(60));
        when(store.abandonRun(RUN, NOW, NOW.minus(Duration.ofMinutes(30)))).thenReturn(notStale);

        assertThat(service.abandon(RUN)).isSameAs(notStale);
        verify(store).abandonRun(RUN, NOW, Instant.parse("2026-10-19T09:30:00Z"));
    }
}
