package com.videogameplatform.api.delivery.ratings;

import com.videogameplatform.api.generated.model.AvailableRatingStatistics;
import com.videogameplatform.api.generated.model.AvailableRatingSummary;
import com.videogameplatform.api.generated.model.Genre;
import com.videogameplatform.api.generated.model.PersonalRating;
import com.videogameplatform.api.generated.model.PersonalRatingItem;
import com.videogameplatform.api.generated.model.RatedGame;
import com.videogameplatform.api.generated.model.RatingDistribution;
import com.videogameplatform.api.generated.model.RatingSummary;
import com.videogameplatform.api.generated.model.UnavailableRatingStatistics;
import com.videogameplatform.ratings.application.RatingStatistics;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Maps provider-independent rating results to the generated HTTP transport. */
@Component
final class RatingApiMapper {
    private final com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper covers;

    RatingApiMapper(com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper covers) {
        this.covers = covers;
    }

    com.videogameplatform.api.generated.model.PersonalRatingPage toResponse(
            com.videogameplatform.ratings.application.PersonalRatingsPage page) {
        return new com.videogameplatform.api.generated.model.PersonalRatingPage(
                page.items().stream()
                        .map(
                                item -> {
                                    var game =
                                            new RatedGame(
                                                    item.gameId(),
                                                    item.slug(),
                                                    item.canonicalTitle(),
                                                    covers.toResponse(item.cover()));
                                    game.setGenres(
                                            item.genres().stream()
                                                    .map(
                                                            term ->
                                                                    new Genre(
                                                                            term.code(),
                                                                            term.name()))
                                                    .collect(
                                                            Collectors.toCollection(
                                                                    LinkedHashSet::new)));
                                    var result =
                                            new PersonalRatingItem(game, toResponse(item.rating()));
                                    result.setRatingSummary(
                                            item.ratingSummary()
                                                    .<RatingSummary>map(
                                                            summary ->
                                                                    new AvailableRatingSummary(
                                                                            "available",
                                                                            summary.mean(),
                                                                            summary.count()))
                                                    .orElseGet(
                                                            () ->
                                                                    new UnavailableRatingStatistics(
                                                                            "unavailable",
                                                                            "RATING_STATISTICS_READ_FAILED")));
                                    return result;
                                })
                        .toList(),
                new com.videogameplatform.api.generated.model.PageMetadata(
                        page.page(), page.pageSize(), page.totalItems(), page.totalPages()));
    }

    PersonalRating toResponse(com.videogameplatform.ratings.application.PersonalRating rating) {
        return new PersonalRating(
                rating.gameId(),
                rating.value(),
                rating.createdAt().atOffset(ZoneOffset.UTC),
                rating.updatedAt().atOffset(ZoneOffset.UTC),
                entityTag(rating.versionToken()));
    }

    AvailableRatingStatistics toResponse(RatingStatistics.Available statistics) {
        var buckets = statistics.distribution();
        return new AvailableRatingStatistics(
                "available",
                statistics.mean(),
                statistics.count(),
                new RatingDistribution(
                        buckets.get(0),
                        buckets.get(1),
                        buckets.get(2),
                        buckets.get(3),
                        buckets.get(4),
                        buckets.get(5),
                        buckets.get(6),
                        buckets.get(7),
                        buckets.get(8),
                        buckets.get(9)));
    }

    static String entityTag(String versionToken) {
        return '"' + versionToken + '"';
    }
}
