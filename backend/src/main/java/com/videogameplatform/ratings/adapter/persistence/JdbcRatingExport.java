package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.ExportPersonalRatings;
import com.videogameplatform.ratings.application.PersonalRating;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** Deterministic keyset batches, no total count and no process-wide collection. */
public final class JdbcRatingExport implements ExportPersonalRatings {
    private final NamedParameterJdbcOperations jdbc;

    public JdbcRatingExport(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void read(String userId, Consumer<Item> consume) {
        String after = "00000000-0000-0000-0000-000000000000";
        int total = 0;
        while (true) {
            var page =
                    jdbc.query(
                            """
                SELECT r.*, g.canonical_title, p.* FROM (
                    SELECT * FROM ratings.rating WHERE user_id = :user AND (:first OR game_id > :after)
                    ORDER BY game_id LIMIT 100
                ) r LEFT JOIN ratings.game_listing g ON g.game_id = r.game_id
                LEFT JOIN LATERAL (
                """
                                    + RatingPlatformRows.AGGREGATED
                                    + ") p ON true ORDER BY r.game_id",
                            Map.of(
                                    "user",
                                    UUID.fromString(userId),
                                    "after",
                                    UUID.fromString(after),
                                    "first",
                                    total == 0),
                            (rs, row) ->
                                    new Item(
                                            rs.getString("game_id"),
                                            rs.getString("canonical_title"),
                                            new PersonalRating(
                                                    rs.getString("game_id"),
                                                    (Integer) rs.getObject("value"),
                                                    RatingPlatformRows.aggregated(rs),
                                                    rs.getTimestamp("created_at").toInstant(),
                                                    rs.getTimestamp("updated_at").toInstant(),
                                                    rs.getString("version_token"))));
            for (Item item : page) {
                if (++total > 10000)
                    throw new IllegalStateException("Export rating budget exceeded");
                consume.accept(item);
                after = item.gameId();
            }
            if (page.size() < 100) return;
        }
    }
}
