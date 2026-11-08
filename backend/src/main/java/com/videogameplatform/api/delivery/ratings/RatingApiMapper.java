package com.videogameplatform.api.delivery.ratings;

import com.videogameplatform.api.generated.model.AvailableRatingStatistics;
import com.videogameplatform.api.generated.model.AvailableRatingSummary;
import com.videogameplatform.api.generated.model.Genre;
import com.videogameplatform.api.generated.model.PersonalRating;
import com.videogameplatform.api.generated.model.PersonalRatingItem;
import com.videogameplatform.api.generated.model.PersonalRatingPlatform;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.PlatformRatingStatistics;
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
public final class RatingApiMapper {
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
                // A direct value exists exactly when the rating is not derived from platforms.
                rating.value() == null
                        ? PersonalRating.ModeEnum.PLATFORMS
                        : PersonalRating.ModeEnum.DIRECT,
                rating.value(),
                rating.contribution(),
                rating.platforms().stream()
                        .map(
                                platform ->
                                        new PersonalRatingPlatform(
                                                new Platform(
                                                        platform.platformId(), platform.name()),
                                                platform.value()))
                        .toList(),
                rating.createdAt().atOffset(ZoneOffset.UTC),
                rating.updatedAt().atOffset(ZoneOffset.UTC),
                entityTag(rating.versionToken()));
    }

    AvailableRatingStatistics toResponse(RatingStatistics.Available statistics) {
        return statistics(statistics);
    }

    /** The available community reading, shared with the public game details. */
    public static AvailableRatingStatistics statistics(RatingStatistics.Available statistics) {
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
                        buckets.get(9)),
                statistics.platforms().stream()
                        .map(
                                platform ->
                                        new PlatformRatingStatistics(
                                                new Platform(
                                                        platform.platformId(), platform.name()),
                                                platform.mean(),
                                                platform.count()))
                        .toList());
    }

    com.videogameplatform.api.generated.model.PersonalRatingInsights toResponse(
            com.videogameplatform.ratings.application.PersonalRatingInsights insights) {
        return new com.videogameplatform.api.generated.model.PersonalRatingInsights(
                insights.evaluatedOn(),
                insights.total(),
                insights.mean(),
                insights.distribution(),
                insights.mostRatedGenres().stream().map(this::genre).toList(),
                insights.bestRatedGenres().stream().map(this::genre).toList(),
                insights.gamesWithoutGenres(),
                extreme(insights.highest()),
                extreme(insights.lowest()),
                insights.activity().stream()
                        .map(
                                month ->
                                        new com.videogameplatform.api.generated.model
                                                .PersonalRatingMonth(
                                                month.month().toString(), month.count()))
                        .toList(),
                insights.olderRatings(),
                platforms(insights.platforms()));
    }

    private com.videogameplatform.api.generated.model.PersonalPlatformInsights platforms(
            com.videogameplatform.ratings.application.PersonalRatingInsights.Platforms platforms) {
        return new com.videogameplatform.api.generated.model.PersonalPlatformInsights(
                        platforms.items().stream()
                                .map(
                                        platform ->
                                                new com.videogameplatform.api.generated.model
                                                        .PersonalPlatformInsight(
                                                        new Platform(
                                                                platform.id(), platform.name()),
                                                        platform.playedGames(),
                                                        platform.scoredGames(),
                                                        platform.mean()))
                                .toList(),
                        platforms.otherPlatforms(),
                        platforms.gamesWithoutPlatforms(),
                        platforms.gamesOnSeveralPlatforms())
                .distribution(
                        new com.videogameplatform.api.generated.model.PersonalPlatformDistribution(
                                platforms.distribution().ps5(),
                                platforms.distribution().switch2(),
                                platforms.distribution().xboxSeries(),
                                platforms.distribution().windowsPc(),
                                platforms.distribution().other()));
    }

    private com.videogameplatform.api.generated.model.PersonalGenreInsight genre(
            com.videogameplatform.ratings.application.PersonalRatingInsights.Genre genre) {
        return new com.videogameplatform.api.generated.model.PersonalGenreInsight(
                new Genre(genre.id(), genre.name()), genre.count(), genre.mean());
    }

    private com.videogameplatform.api.generated.model.PersonalRatingExtreme extreme(
            com.videogameplatform.ratings.application.PersonalRatingInsights.Extreme extreme) {
        if (extreme == null) return null;
        return new com.videogameplatform.api.generated.model.PersonalRatingExtreme(
                new RatedGame(
                        extreme.gameId(),
                        extreme.slug(),
                        extreme.canonicalTitle(),
                        covers.toResponse(extreme.cover())),
                extreme.value(),
                extreme.tiedGames());
    }

    static String entityTag(String versionToken) {
        return '"' + versionToken + '"';
    }
}
