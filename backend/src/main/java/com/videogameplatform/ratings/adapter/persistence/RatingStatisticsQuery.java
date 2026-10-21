package com.videogameplatform.ratings.adapter.persistence;

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
    static final String COMMUNITY =
            """
            WITH scoped AS MATERIALIZED (
                SELECT contribution FROM ratings.rating WHERE game_id = :game
            ), buckets AS (
                SELECT %s AS score, count(*) AS bucket_count FROM scoped GROUP BY 1
            ), totals AS (
                SELECT count(*) AS total, %s AS mean FROM scoped
            )
            SELECT series.score, coalesce(buckets.bucket_count, 0) AS bucket_count,
                   totals.total, totals.mean
            FROM generate_series(1, 10) series(score)
            CROSS JOIN totals
            LEFT JOIN buckets ON buckets.score = series.score
            ORDER BY series.score
            """
                    .formatted(
                            ContributionMean.bucket("contribution"),
                            ContributionMean.exposed("contribution"));
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
                    int count = 0;
                    while (rs.next()) {
                        buckets.add(Math.toIntExact(rs.getLong("bucket_count")));
                        mean = rs.getBigDecimal("mean");
                        count = Math.toIntExact(rs.getLong("total"));
                    }
                    return new RatingStatistics.Available(mean, count, buckets, platforms);
                });
    }
}
