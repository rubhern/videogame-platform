package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedImage;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.Selection;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for the featured selection: why it holds what it holds, how current
 * its popularity evidence is, whether the visitor chose another month, and which kind of image the
 * featured release of the month presents. No game, month value, image or popularity value is ever
 * a dimension.
 */
@Component
final class FeaturedReleaseApiMetrics {

    static final String SELECTION = "catalogue.featured.selection";

    private final MeterRegistry registry;

    FeaturedReleaseApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordSelection(
            Selection selection, boolean monthRequested, List<FeaturedReleasesResult.Item> items) {
        registry.counter(
                        SELECTION,
                        "status",
                        status(selection),
                        "freshness",
                        freshness(selection),
                        "month",
                        monthRequested ? "requested" : "current",
                        "lead_image",
                        items.isEmpty() ? "none" : image(items.getFirst().image()))
                .increment();
    }

    private static String image(FeaturedImage image) {
        return switch (image) {
            case FeaturedImage.Provider provider ->
                    switch (provider.kind()) {
                        case ARTWORK -> "artwork";
                        case SCREENSHOT -> "screenshot";
                        case COVER -> "cover";
                    };
            case FeaturedImage.Product _ -> "fallback";
        };
    }

    private static String status(Selection selection) {
        return switch (selection) {
            case Selection.Ranked _ -> "ranked";
            case Selection.PopularityUnavailable _ -> "popularity_unavailable";
            case Selection.NoQualifyingReleases _ -> "no_qualifying_releases";
        };
    }

    private static String freshness(Selection selection) {
        return switch (selection) {
            case Selection.Ranked ranked ->
                    switch (ranked.popularityFreshness()) {
                        case FRESH -> "fresh";
                        case STALE -> "stale";
                    };
            case Selection.PopularityUnavailable _, Selection.NoQualifyingReleases _ -> "none";
        };
    }
}
