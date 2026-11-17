package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedImage;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.Selection;
import com.videogameplatform.catalogue.application.releases.UpcomingHighlightsResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality telemetry for a platform's upcoming highlights: why the selection holds what
 * it holds, how current its Hypes evidence is, whether the scope is one platform or a family, and
 * which kind of image the first game presents. No platform, game, date or Hypes value is ever a
 * dimension.
 */
@Component
final class UpcomingHighlightsApiMetrics {

    static final String SELECTION = "catalogue.upcoming_highlights.selection";

    private final MeterRegistry registry;

    UpcomingHighlightsApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordSelection(UpcomingHighlightsResult result) {
        registry.counter(
                        SELECTION,
                        "status",
                        switch (result.selection()) {
                            case Selection.Ranked _ -> "ranked";
                            case Selection.PopularityUnavailable _ -> "popularity_unavailable";
                            case Selection.NoQualifyingReleases _ -> "no_qualifying_releases";
                        },
                        "freshness",
                        switch (result.selection()) {
                            case Selection.Ranked ranked ->
                                    switch (ranked.popularityFreshness()) {
                                        case FRESH -> "fresh";
                                        case STALE -> "stale";
                                    };
                            case Selection.PopularityUnavailable _,
                                    Selection.NoQualifyingReleases _ ->
                                    "none";
                        },
                        "scope",
                        result.platforms().size() == 1 ? "platform" : "family",
                        "lead_image",
                        result.items().isEmpty()
                                ? "none"
                                : image(result.items().getFirst().image()))
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
}
