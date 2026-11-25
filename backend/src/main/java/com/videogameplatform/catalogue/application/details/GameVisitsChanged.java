package com.videogameplatform.catalogue.application.details;

import java.util.List;
import java.util.UUID;

/** A synchronized page of current catalogue visit signals after the persistence guard was applied. */
public record GameVisitsChanged(List<Sample> samples) {
    public GameVisitsChanged {
        samples = List.copyOf(samples);
    }

    /** A null share means that the stored signal for the game was cleared or is unknown. */
    public record Sample(UUID gameId, Double share) {}
}
