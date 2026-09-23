package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
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
            return RatingStatisticsQuery.read(jdbc, gameId);
        } catch (DataAccessException | IllegalArgumentException | ArithmeticException _) {
            // Local aggregate failure is isolated from the already coherent catalogue response.
            return new RatingStatistics.Unavailable();
        }
    }
}
