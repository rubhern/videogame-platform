package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.port.RatingSummariesReadPort;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** SQL aggregates only selected Games; ratings and vote histories never cross into Java. */
public final class JdbcRatingSummariesReadAdapter implements RatingSummariesReadPort {
    private final NamedParameterJdbcOperations jdbc;

    public JdbcRatingSummariesReadAdapter(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Optional<RatingStatistics.Summary>> read(Set<String> gameIds) {
        try {
            var result = new HashMap<String, Optional<RatingStatistics.Summary>>();
            gameIds.forEach(
                    id -> result.put(id, Optional.of(new RatingStatistics.Summary(null, 0))));
            jdbc.query(
                    """
                    SELECT game_id, round(avg(value)::numeric, 1) AS mean, count(*) AS count
                    FROM ratings.rating WHERE game_id IN (:games) GROUP BY game_id
                    """,
                    Map.of("games", gameIds.stream().map(UUID::fromString).toList()),
                    rs -> {
                        while (rs.next())
                            result.put(
                                    rs.getString("game_id"),
                                    Optional.of(
                                            new RatingStatistics.Summary(
                                                    rs.getBigDecimal("mean"),
                                                    Math.toIntExact(rs.getLong("count")))));
                        return result;
                    });
            return Map.copyOf(result);
        } catch (DataAccessException | IllegalArgumentException | ArithmeticException _) {
            var unavailable = new HashMap<String, Optional<RatingStatistics.Summary>>();
            gameIds.forEach(id -> unavailable.put(id, Optional.empty()));
            return Map.copyOf(unavailable);
        }
    }
}
