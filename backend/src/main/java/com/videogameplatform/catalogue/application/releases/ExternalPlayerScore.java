package com.videogameplatform.catalogue.application.releases;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/** Current work-level player sentiment on the declared 0–100 scale, independent of provider payloads and product ratings. */
public record ExternalPlayerScore(
        Source source,
        BigDecimal value,
        long count,
        URI attribution,
        Instant observedAt,
        Optional<Instant> providerRecordUpdatedAt) {
    public enum Source {
        IGDB
    }
}
