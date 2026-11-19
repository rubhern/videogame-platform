package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.RecentRatingCounts;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

public class JdbcRecentRatingCounts implements RecentRatingCounts {
    private final NamedParameterJdbcOperations jdbc;

    public JdbcRecentRatingCounts(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Count> ranking(Instant start, Instant end) {
        return jdbc.query(
                """
                SELECT game_id, count(*) AS activity
                FROM ratings.rating
                WHERE created_at >= :start AND created_at < :end
                GROUP BY game_id
                ORDER BY activity DESC, game_id
                LIMIT 10
                """,
                Map.of(
                        "start",
                        start.atOffset(ZoneOffset.UTC),
                        "end",
                        end.atOffset(ZoneOffset.UTC)),
                (row, index) -> new Count(row.getString("game_id"), row.getLong("activity")));
    }
}
