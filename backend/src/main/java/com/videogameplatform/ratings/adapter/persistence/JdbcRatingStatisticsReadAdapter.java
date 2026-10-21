package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL aggregates active ratings in one read-only snapshot, so the community and platform
 * readings agree; ten buckets and at most one row per scored platform cross into Java.
 */
public final class JdbcRatingStatisticsReadAdapter implements RatingStatisticsReadPort {
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations snapshot;

    public JdbcRatingStatisticsReadAdapter(
            NamedParameterJdbcOperations jdbc, TransactionOperations snapshot) {
        this.jdbc = jdbc;
        this.snapshot = snapshot;
    }

    @Override
    public RatingStatistics read(String gameId) {
        try {
            return Objects.requireNonNull(
                    snapshot.execute(status -> RatingStatisticsQuery.read(jdbc, gameId)));
        } catch (DataAccessException
                | TransactionException
                | IllegalArgumentException
                | ArithmeticException _) {
            // Local aggregate failure is isolated from the already coherent catalogue response.
            return new RatingStatistics.Unavailable();
        }
    }
}
