package com.videogameplatform.ratings.adapter.observability;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RatingActivityMetricsTest {
    @Test
    void brokenTelemetryCannotTurnACommittedRatingIntoAFailedCommand() {
        MeterRegistry registry = mock(MeterRegistry.class);
        Counter counter = mock(Counter.class);
        when(registry.counter(anyString(), anyString(), anyString())).thenReturn(counter);
        doThrow(new IllegalStateException("unavailable")).when(counter).increment();
        RatingActivityMetrics metrics = new RatingActivityMetrics(registry);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.afterCommit(RatingActivityMetrics.Action.CREATED);
            assertThatCode(
                            () ->
                                    TransactionSynchronizationManager.getSynchronizations()
                                            .forEach(TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
