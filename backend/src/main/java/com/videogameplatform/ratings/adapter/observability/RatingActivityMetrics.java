package com.videogameplatform.ratings.adapter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Process observations of committed commands, not an organic or durable event ledger. */
public final class RatingActivityMetrics {
    public enum Action {
        CREATED,
        UPDATED,
        DELETED
    }

    private static final Logger LOG = LoggerFactory.getLogger(RatingActivityMetrics.class);
    private final EnumMap<Action, Counter> counters = new EnumMap<>(Action.class);

    public RatingActivityMetrics(MeterRegistry registry) {
        for (Action action : Action.values()) {
            counters.put(
                    action,
                    registry.counter(
                            "ratings.personal.activity",
                            "action",
                            action.name().toLowerCase(Locale.ROOT)));
        }
    }

    public void afterCommit(Action action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return; // Never guess that a transaction committed.
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            counters.get(action).increment();
                        } catch (RuntimeException _) {
                            // A committed product write must not fail because its telemetry failed.
                            LOG.warn("Rating activity metric unavailable");
                        }
                    }
                });
    }
}
