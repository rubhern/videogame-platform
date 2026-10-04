package com.videogameplatform.catalogue.application.releases;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Provider-independent result of UC-010. {@code items} is the ranking, highest popularity first:
 * the first item is the month's featured release and up to five more follow. Each item presents
 * only the game's qualifying releases inside the month, at most one per platform.
 */
public record FeaturedReleasesResult(
        YearMonth month,
        LocalDate evaluatedOn,
        BrowseReleasesResult.Window window,
        Selection selection,
        List<Item> items) {

    public FeaturedReleasesResult {
        items = List.copyOf(items);
        if (items.isEmpty() == selection instanceof Selection.Ranked) {
            throw new IllegalArgumentException("Only a ranked selection holds featured items");
        }
    }

    /**
     * One ranked game: its presented qualifying releases with its cover, plus the landscape image
     * and the optional title logo featured discovery presents.
     */
    public record Item(
            BrowseReleasesResult.Item release, FeaturedImage image, Optional<FeaturedLogo> logo) {
        public Item {
            java.util.Objects.requireNonNull(release, "release");
            java.util.Objects.requireNonNull(image, "image");
            logo = java.util.Objects.requireNonNull(logo, "logo");
        }
    }

    /**
     * The landscape image of a featured game after the featured-media policy and ADR-0001 ran:
     * an approved provider rendition with its attribution, or the product-owned fallback.
     */
    public sealed interface FeaturedImage {

        /** Which provider image the policy settled on. */
        enum Kind {
            ARTWORK,
            SCREENSHOT,
            COVER
        }

        /** Whether a frame may crop the image to fill it, or must show it whole. */
        enum Presentation {
            FILL,
            CONTAIN
        }

        /**
         * {@code url} suits the featured release of the month and {@code compactUrl} the other
         * featured releases; a {@code CONTAIN} image is shown whole, never stretched or cropped.
         */
        record Provider(
                Kind kind,
                Presentation presentation,
                URI url,
                URI compactUrl,
                String alternativeText,
                CatalogueCover.Attribution attribution)
                implements FeaturedImage {}

        /** No provider image is usable: the product-owned landscape fallback fills the frame. */
        record Product(String alternativeText) implements FeaturedImage {}
    }

    /** An approved transparent provider logo that may replace the rendered title. */
    public record FeaturedLogo(
            URI url, String alternativeText, CatalogueCover.Attribution attribution) {}

    /** Why the selection holds what it holds; popularity is attention, never quality. */
    public sealed interface Selection {

        /**
         * At least one qualifying game has a popularity signal. Freshness and the observation time
         * belong to the oldest signal among the ranked games, so the ranking is at least this
         * current; a stale ranking is still the last valid local one.
         */
        record Ranked(CatalogueFreshness popularityFreshness, Instant popularityObservedAt)
                implements Selection {}

        /** The month has qualifying releases, but none of their games has a popularity signal. */
        record PopularityUnavailable() implements Selection {}

        /** The local catalogue holds no qualifying release inside the month. */
        record NoQualifyingReleases() implements Selection {}
    }
}
