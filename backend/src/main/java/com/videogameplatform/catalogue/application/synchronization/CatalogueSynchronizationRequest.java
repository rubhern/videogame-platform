package com.videogameplatform.catalogue.application.synchronization;

import java.time.LocalDate;

/** Operator-selected inclusive release-date window. */
public record CatalogueSynchronizationRequest(LocalDate from, LocalDate to) {
    public CatalogueSynchronizationRequest {
        if (from == null || to == null || to.isBefore(from) || to.equals(LocalDate.MAX)) {
            throw new IllegalArgumentException("Invalid catalogue synchronization date window");
        }
    }

    public LocalDate toExclusive() {
        return to.plusDays(1);
    }
}
