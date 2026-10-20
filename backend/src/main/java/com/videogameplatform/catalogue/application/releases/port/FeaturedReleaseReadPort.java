package com.videogameplatform.catalogue.application.releases.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Outbound query port for one calendar period's featured releases (a week or a month) from the
 * current publication.
 *
 * <p>The store selects the qualifying releases inside the period, ranks their games by the local
 * popularity signal with a unique final tie-breaker, limits the ranking and presents at most one
 * release per platform, so request memory stays {@code O(limit x releaseGroupLimit)}.
 */
public interface FeaturedReleaseReadPort {

    /** Empty when no catalogue publication exists yet. */
    Optional<Result> findFeaturedReleases(Criteria criteria);

    /**
     * The period as its inclusive first and last day, the ranking bound and the release bound. A
     * release qualifies only when its whole known date period lies inside these days, so a week
     * admits exact days alone. A non-empty {@code platformIds} admits only releases on one of
     * those platforms, before any game is ranked.
     */
    record Criteria(
            LocalDate periodStart,
            LocalDate periodEnd,
            int limit,
            int releaseGroupLimit,
            List<String> platformIds) {
        public Criteria {
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
            if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
                throw new IllegalArgumentException("A featured period needs ordered bounds");
            }
            if (limit < 1 || releaseGroupLimit < 1) {
                throw new IllegalArgumentException("A featured ranking must be bounded");
            }
        }

        /** Every platform. */
        public Criteria(
                LocalDate periodStart, LocalDate periodEnd, int limit, int releaseGroupLimit) {
            this(periodStart, periodEnd, limit, releaseGroupLimit, List.of());
        }
    }

    /**
     * {@code items} is the bounded ranking. {@code qualifyingReleases} states whether the period
     * holds any qualifying release at all, which tells an unranked period apart from an empty one.
     * {@code platformsSupported} is false when a requested platform is not part of the taxonomy,
     * in which case nothing was ranked.
     */
    record Result(
            String publicationVersion,
            boolean qualifyingReleases,
            List<Item> items,
            boolean platformsSupported) {
        public Result {
            items = List.copyOf(items);
        }

        public Result(String publicationVersion, boolean qualifyingReleases, List<Item> items) {
            this(publicationVersion, qualifyingReleases, items, true);
        }
    }

    /**
     * One ranked game with its presented qualifying releases, the time its popularity signal was
     * last observed, and its stored hero/card images and optional card logo; the signal value itself never
     * leaves the store.
     */
    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCoverReference cover,
            Instant popularityObservedAt,
            Optional<MediaReference> image,
            Optional<MediaReference> cardImage,
            Optional<MediaReference> logo,
            List<ReleaseRow> releases,
            List<GameDetailsResult.Term> genres,
            Optional<GameDetailsResult.Summary> summary) {
        public Item {
            cardImage = java.util.Objects.requireNonNull(cardImage, "cardImage");
            image = java.util.Objects.requireNonNull(image, "image");
            logo = java.util.Objects.requireNonNull(logo, "logo");
            releases = List.copyOf(releases);
            genres = List.copyOf(genres);
            summary = java.util.Objects.requireNonNull(summary, "summary");
        }
    }

    /**
     * A stored featured-media selection as read, before the approved delivery rendition is
     * resolved: the provider, its opaque image reference and attribution page, and the metadata
     * the presentation depends on.
     */
    record MediaReference(
            FeaturedMediaPolicy.ImageKind kind,
            String provider,
            String reference,
            int width,
            int height,
            boolean transparent,
            String sourceUrl) {}
}
