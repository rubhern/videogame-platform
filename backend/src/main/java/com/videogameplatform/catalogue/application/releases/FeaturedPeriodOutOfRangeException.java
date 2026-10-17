package com.videogameplatform.catalogue.application.releases;

import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase.Granularity;
import java.util.Objects;

/**
 * The requested featured week or month lies outside the navigation bounds at the trusted evaluation
 * date. Featured discovery presents a bounded range of periods around the current one, never an
 * arbitrary history or a future beyond the configured acquisition horizon.
 */
public final class FeaturedPeriodOutOfRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Granularity granularity;

    public FeaturedPeriodOutOfRangeException(Granularity granularity) {
        super("A featured period must fall inside the navigation bounds");
        this.granularity = Objects.requireNonNull(granularity, "granularity");
    }

    /** Which period parameter was out of range. */
    public Granularity granularity() {
        return granularity;
    }
}
