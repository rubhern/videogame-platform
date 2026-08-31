package com.videogameplatform.catalogue.application.internal;

import com.videogameplatform.catalogue.domain.FreshnessStatus;
import java.time.Duration;
import java.time.Instant;

/** Operational threshold used to derive freshness at the request evaluation instant. */
public record CatalogueFreshnessPolicy(Duration threshold) {
    public CatalogueFreshnessPolicy {
        if (threshold == null || threshold.isNegative() || threshold.isZero()) {
            throw new IllegalArgumentException("Freshness threshold must be positive");
        }
        if (threshold.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Freshness threshold cannot exceed 365 days");
        }
    }

    FreshnessStatus status(Instant lastSynchronizedAt, Instant evaluatedAt) {
        return lastSynchronizedAt.plus(threshold).isBefore(evaluatedAt)
                ? FreshnessStatus.STALE
                : FreshnessStatus.FRESH;
    }
}
