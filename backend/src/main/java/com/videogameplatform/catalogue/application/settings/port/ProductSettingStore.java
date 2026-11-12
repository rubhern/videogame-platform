package com.videogameplatform.catalogue.application.settings.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable operator overrides of product settings, a single revision counter that every change
 * advances, and the audit trail of changes. Keys are the registry's stable keys; a key the
 * application no longer registers is kept but never read as a setting.
 */
public interface ProductSettingStore {

    Stored read();

    /** The most recent changes, newest revision first, at most {@code limit}. */
    List<Change> recentChanges(int limit);

    /**
     * Applies every write and its audit row in one transaction, only while the stored revision
     * still equals {@code expectedRevision}; the revision then advances by one.
     *
     * @return the new revision, or empty when another change came first and nothing was written
     */
    Optional<Long> apply(
            long expectedRevision, List<Write> writes, String operatorId, Instant changedAt);

    record Stored(long revision, Map<String, StoredValue> overrides) {
        public Stored {
            overrides = Map.copyOf(overrides);
        }
    }

    /** An operator's value and when it was set. */
    record StoredValue(BigDecimal value, Instant changedAt) {}

    /**
     * One setting's change: {@code value} empty restores the default. {@code previous} and {@code
     * next} are the effective values before and after, recorded for the audit.
     */
    record Write(String key, Optional<BigDecimal> value, BigDecimal previous, BigDecimal next) {}

    record Change(
            long revision,
            String key,
            BigDecimal previous,
            BigDecimal next,
            boolean restoredDefault,
            Instant changedAt) {}
}
