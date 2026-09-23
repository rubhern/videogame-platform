package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.RatingStatistics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** One database-side aggregate query returning a fixed ten-bucket result. */
final class RatingStatisticsQuery {
    private RatingStatisticsQuery() {}

    static RatingStatistics.Available read(NamedParameterJdbcOperations jdbc, String gameId) {
        return jdbc.query(
                """
                WITH buckets AS (
                    SELECT value, count(*) AS bucket_count FROM ratings.rating
                    WHERE game_id = :game GROUP BY value
                )
                SELECT score, coalesce(bucket_count, 0) AS bucket_count,
                       sum(coalesce(bucket_count, 0)) OVER () AS total,
                       round(sum(score * coalesce(bucket_count, 0)) OVER ()::numeric /
                             nullif(sum(coalesce(bucket_count, 0)) OVER (), 0), 1) AS mean
                FROM generate_series(1, 10) score LEFT JOIN buckets ON buckets.value = score
                ORDER BY score
                """,
                Map.of("game", UUID.fromString(gameId)),
                rs -> {
                    var buckets = new ArrayList<Integer>(10);
                    BigDecimal mean = null;
                    int count = 0;
                    while (rs.next()) {
                        buckets.add(Math.toIntExact(rs.getLong("bucket_count")));
                        mean = rs.getBigDecimal("mean");
                        count = Math.toIntExact(rs.getLong("total"));
                    }
                    return new RatingStatistics.Available(mean, count, buckets);
                });
    }
}
