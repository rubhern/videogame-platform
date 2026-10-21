package com.videogameplatform.ratings.application;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

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
        long olderRatings) {
    public PersonalRatingInsights {
        distribution = List.copyOf(distribution);
        mostRatedGenres = List.copyOf(mostRatedGenres);
        bestRatedGenres = List.copyOf(bestRatedGenres);
        activity = List.copyOf(activity);
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
