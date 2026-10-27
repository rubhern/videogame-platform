package com.videogameplatform.catalogue.application.releases.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult.Taxonomy;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outbound query port for UC-020 from the current publication.
 *
 * <p>The store selects the qualifying releases of the platform scope inside the horizon, keeps the
 * distinct games still new to the scope, ranks them by the local Hypes signal with a unique final
 * tie-breaker, limits the ranking and presents one release per ranked game, so request memory stays
 * {@code O(limit)}.
 */
public interface UpcomingHighlightsReadPort {

    /** Empty when no catalogue publication exists yet. */
    Optional<Result> findUpcomingHighlights(Criteria criteria);

    /**
     * The scope's product platform identifiers, the trusted evaluation date, the horizon's
     * inclusive last day and the ranking bound.
     */
    record Criteria(
            List<String> platformIds, LocalDate evaluatedOn, LocalDate horizonEnd, int limit) {
        public Criteria {
            platformIds = List.copyOf(platformIds);
            Objects.requireNonNull(evaluatedOn, "evaluatedOn");
            if (platformIds.isEmpty()) {
                throw new IllegalArgumentException("A platform scope names at least one platform");
            }
            if (horizonEnd == null || horizonEnd.isBefore(evaluatedOn)) {
                throw new IllegalArgumentException("An upcoming horizon needs ordered bounds");
            }
            if (limit < 1) {
                throw new IllegalArgumentException("An upcoming ranking must be bounded");
            }
        }
    }

    /**
     * {@code platforms} holds the requested platforms the taxonomy knows; when it is shorter than
     * the request nothing was ranked. {@code candidates} states whether any game qualifies at all,
     * which tells an unranked scope apart from an empty one.
     */
    record Result(List<Taxonomy> platforms, boolean candidates, List<Item> items) {
        public Result {
            platforms = List.copyOf(platforms);
            items = List.copyOf(items);
        }
    }

    /**
     * One ranked game in ranking order, the time its Hypes were last observed, its stored hero and
     * card images, and its presented release with the persisted status evidence.
     */
    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCoverReference cover,
            Instant popularityObservedAt,
            Optional<MediaReference> image,
            Optional<MediaReference> cardImage,
            List<GameDetailsResult.Term> genres,
            Taxonomy platform,
            Taxonomy region,
            ReleaseDate releaseDate,
            ReleaseStatus status) {
        public Item {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(cardImage, "cardImage");
            genres = List.copyOf(genres);
        }
    }
}
