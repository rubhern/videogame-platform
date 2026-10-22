package com.videogameplatform.ratings.application;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-size description of one user's current ratings; never a historical score series. Every
 * figure counts each rating's one contribution (its direct value or the mean of its scored
 * platforms); the distribution places it in its half-up integer bucket.
 */
public record PersonalRatingInsights(
        LocalDate evaluatedOn,
        long total,
        BigDecimal mean,
        List<Long> distribution,
        List<Genre> mostRatedGenres,
        List<Genre> bestRatedGenres,
        long gamesWithoutGenres,
        Extreme highest,
        Extreme lowest,
        List<Month> activity,
        long olderRatings,
        Platforms platforms) {
    public PersonalRatingInsights {
        distribution = List.copyOf(distribution);
        mostRatedGenres = List.copyOf(mostRatedGenres);
        bestRatedGenres = List.copyOf(bestRatedGenres);
        activity = List.copyOf(activity);
        Objects.requireNonNull(platforms, "platforms");
    }

    /**
     * Only the platforms the user recorded playing on: a game counts on each of its platforms, so
     * the items overlap and never partition the total.
     */
    public record Platforms(
            List<Platform> items,
            long otherPlatforms,
            long gamesWithoutPlatforms,
            long gamesOnSeveralPlatforms) {
        public Platforms {
            items = List.copyOf(items);
        }
    }

    /**
     * One recorded platform: the games played there, how many of them carry an explicit score on
     * it and those scores' mean, which is {@code null} exactly when none does.
     */
    public record Platform(
            String id, String name, long playedGames, long scoredGames, BigDecimal mean) {
        public Platform {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            if (playedGames < 1
                    || scoredGames < 0
                    || scoredGames > playedGames
                    || (scoredGames == 0) != (mean == null)) {
                throw new IllegalArgumentException("Invalid personal platform insight");
            }
        }
    }

    public record Genre(String id, String name, long count, BigDecimal mean) {}

    public record Extreme(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCover cover,
            BigDecimal value,
            long tiedGames) {}

    public record Month(YearMonth month, long count) {}
}
