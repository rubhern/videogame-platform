package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** PostgreSQL aggregates active ratings; at most ten buckets cross into Java. */
public final class JdbcRatingStatisticsReadAdapter implements RatingStatisticsReadPort {
    private final NamedParameterJdbcOperations jdbc;

    public JdbcRatingStatisticsReadAdapter(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RatingStatistics read(String gameId) {
        try {
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
                        var buckets = new java.util.ArrayList<Integer>(10);
                        BigDecimal mean = null;
                        int count = 0;
                        while (rs.next()) {
                            buckets.add(Math.toIntExact(rs.getLong("bucket_count")));
                            mean = rs.getBigDecimal("mean");
                            count = Math.toIntExact(rs.getLong("total"));
                        }
                        return new RatingStatistics.Available(mean, count, buckets);
                    });
        } catch (DataAccessException | IllegalArgumentException | ArithmeticException _) {
            // Local aggregate failure is isolated from the already coherent catalogue response.
            return new RatingStatistics.Unavailable();
        }
    }
}
