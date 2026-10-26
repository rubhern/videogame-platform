package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase;
import com.videogameplatform.ratings.application.RatingStatistics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/**
 * Database-side aggregates of one game: a fixed ten-bucket community result over one contribution
 * per rating, and one row per platform someone scored. Callers run both inside one snapshot.
 */
final class RatingStatisticsQuery {
    private static final String CONTRIBUTION = "contribution";
    static final String COMMUNITY =
            """
            WITH scoped AS MATERIALIZED (
                SELECT contribution FROM ratings.rating WHERE game_id = :game
            ), buckets AS (
                SELECT %s AS score, count(*) AS bucket_count FROM scoped GROUP BY 1
            ), totals AS (
                SELECT count(*) AS total, %s AS mean, %s AS exact_mean FROM scoped
            )
            SELECT series.score, coalesce(buckets.bucket_count, 0) AS bucket_count,
                   totals.total, totals.mean, totals.exact_mean
            FROM generate_series(1, 10) series(score)
            CROSS JOIN totals
            LEFT JOIN buckets ON buckets.score = series.score
            ORDER BY series.score
            """
                    .formatted(
                            ContributionMean.bucket(CONTRIBUTION),
                            ContributionMean.exposed(CONTRIBUTION),
                            ContributionMean.exact(CONTRIBUTION));
    // Integer means are exact at a half-way decimal, so they round directly.
    static final String PLATFORMS =
            """
            SELECT p.platform_id::text AS platform_id, l.display_name,
                   round(avg(p.value), 1) AS mean, count(*) AS rating_count
            FROM ratings.rating_platform p JOIN ratings.platform_label l USING (platform_id)
            WHERE p.game_id = :game AND p.value IS NOT NULL
            GROUP BY p.platform_id, l.display_name
            ORDER BY count(*) DESC, lower(l.display_name), p.platform_id
            """;

    private RatingStatisticsQuery() {}

    static RatingStatistics.Available read(NamedParameterJdbcOperations jdbc, String gameId) {
        return readProjected(jdbc, gameId).statistics();
    }

    /**
     * The game's statistics with the unrounded mean they expose at one decimal, both from the same
     * aggregate, for the Catalogue projection that ranks by it (ADR-0029, ADR-0034).
     */
    static Projected readProjected(NamedParameterJdbcOperations jdbc, String gameId) {
        var game = Map.of("game", UUID.fromString(gameId));
        var platforms =
                jdbc.query(
                        PLATFORMS,
                        game,
                        (rs, row) ->
                                new RatingStatistics.PlatformStatistics(
                                        rs.getString("platform_id"),
                                        rs.getString("display_name"),
                                        rs.getBigDecimal("mean"),
                                        Math.toIntExact(rs.getLong("rating_count"))));
        return jdbc.query(
                COMMUNITY,
                game,
                rs -> {
                    var buckets = new ArrayList<Integer>(10);
                    BigDecimal mean = null;
                    BigDecimal exactMean = null;
                    int count = 0;
                    while (rs.next()) {
                        buckets.add(Math.toIntExact(rs.getLong("bucket_count")));
                        mean = rs.getBigDecimal("mean");
                        exactMean = rs.getBigDecimal("exact_mean");
                        count = Math.toIntExact(rs.getLong("total"));
                    }
                    return new Projected(
                            new RatingStatistics.Available(mean, count, buckets, platforms),
                            new RecordCommunityScoresUseCase.CommunityScore(exactMean, count));
                });
    }

    /** One aggregate read twice: as Ratings exposes it and as Catalogue projects it. */
    record Projected(
            RatingStatistics.Available statistics,
            RecordCommunityScoresUseCase.CommunityScore score) {}
}
