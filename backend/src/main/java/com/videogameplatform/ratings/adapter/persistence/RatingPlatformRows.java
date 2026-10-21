package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.PersonalRating;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** Reads the played platforms of ratings, each with its Ratings-owned label, in display order. */
final class RatingPlatformRows {
    /** Columns {@code platform_ids}, {@code platform_names} and {@code platform_values}. */
    static final String AGGREGATED =
            """
            SELECT array_agg(p.platform_id::text ORDER BY lower(l.display_name), p.platform_id)
                       AS platform_ids,
                   array_agg(l.display_name ORDER BY lower(l.display_name), p.platform_id)
                       AS platform_names,
                   array_agg(p.value::integer ORDER BY lower(l.display_name), p.platform_id)
                       AS platform_values
            FROM ratings.rating_platform p JOIN ratings.platform_label l USING (platform_id)
            WHERE p.user_id = r.user_id AND p.game_id = r.game_id
            """;

    private static final String FOR_GAMES =
            """
            SELECT p.game_id::text AS game_id, p.platform_id::text AS platform_id,
                   l.display_name, p.value
            FROM ratings.rating_platform p JOIN ratings.platform_label l USING (platform_id)
            WHERE p.user_id = :user AND p.game_id IN (:games)
            ORDER BY p.game_id, lower(l.display_name), p.platform_id
            """;

    private RatingPlatformRows() {}

    /** The platforms of one row selected with {@link #AGGREGATED}; empty arrays read as null. */
    static List<PersonalRating.PlayedPlatform> aggregated(ResultSet rs) throws SQLException {
        Object[] ids = array(rs.getArray("platform_ids"));
        Object[] names = array(rs.getArray("platform_names"));
        Object[] values = array(rs.getArray("platform_values"));
        if (names.length != ids.length || values.length != ids.length) {
            throw new SQLException("Rating platform columns differ in length");
        }
        var platforms = new ArrayList<PersonalRating.PlayedPlatform>(ids.length);
        for (int index = 0; index < ids.length; index++) {
            platforms.add(
                    new PersonalRating.PlayedPlatform(
                            (String) ids[index],
                            (String) names[index],
                            values[index] == null ? null : ((Number) values[index]).intValue()));
        }
        return platforms;
    }

    /** The platforms of one user's ratings of {@code gameIds}: one bounded query for a page. */
    static Map<String, List<PersonalRating.PlayedPlatform>> forGames(
            NamedParameterJdbcOperations jdbc, String userId, Collection<String> gameIds) {
        var platforms = new HashMap<String, List<PersonalRating.PlayedPlatform>>();
        if (gameIds.isEmpty()) {
            return platforms;
        }
        jdbc.query(
                FOR_GAMES,
                Map.of(
                        "user",
                        UUID.fromString(userId),
                        "games",
                        gameIds.stream().map(UUID::fromString).toList()),
                (RowCallbackHandler)
                        rs ->
                                platforms
                                        .computeIfAbsent(
                                                rs.getString("game_id"),
                                                ignored -> new ArrayList<>())
                                        .add(
                                                new PersonalRating.PlayedPlatform(
                                                        rs.getString("platform_id"),
                                                        rs.getString("display_name"),
                                                        rs.getObject("value") == null
                                                                ? null
                                                                : rs.getInt("value"))));
        return platforms;
    }

    private static Object[] array(Array array) throws SQLException {
        return array == null ? new Object[0] : (Object[]) array.getArray();
    }
}
