package com.videogameplatform.catalogue.adapter.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The compact release summary (#187) as SQL, shared by every read that lists games with one: the
 * exact distinct-platform count, the earliest and latest known years and a bounded, ordered head
 * of distinct platforms, aggregated over the game's complete stored release set of the current
 * publication. Each lateral aggregates to exactly one row per game, so it can neither multiply nor
 * drop the games it joins; it runs only for the page's games.
 */
public final class CompactReleaseSummarySql {

    private CompactReleaseSummarySql() {}

    /** The columns {@link #laterals} adds to each game row. */
    public static final String COLUMNS =
            """
            summary.total_platforms,
            summary.earliest_known_year,
            summary.latest_known_year,
            summary_head.platform_ids AS summary_platform_ids,
            summary_head.platform_names AS summary_platform_names""";

    /**
     * Two {@code CROSS JOIN LATERAL}s, {@code summary} and {@code summary_head}, for the game in
     * {@code gameColumn}; they bind {@code :publicationId} and {@code :summaryPlatformLimit}.
     */
    public static String laterals(String gameColumn) {
        return """
                CROSS JOIN LATERAL (
                    SELECT count(DISTINCT rs.platform_id)::integer AS total_platforms,
                           extract(YEAR FROM min(rs.period_start))::integer
                               AS earliest_known_year,
                           extract(YEAR FROM max(rs.period_start))::integer
                               AS latest_known_year
                    FROM catalogue.release_snapshot rs
                    WHERE rs.publication_id = CAST(:publicationId AS uuid)
                      AND rs.game_id = %1$s
                ) summary
                CROSS JOIN LATERAL (
                    SELECT array_agg(head.platform_id::text
                                     ORDER BY head.sort_name, head.platform_id)
                               AS platform_ids,
                           array_agg(head.display_name
                                     ORDER BY head.sort_name, head.platform_id)
                               AS platform_names
                    FROM (
                        SELECT p.platform_id,
                               p.display_name,
                               lower(p.display_name) AS sort_name
                        FROM catalogue.platform p
                        WHERE p.platform_id IN (
                            SELECT rs.platform_id
                            FROM catalogue.release_snapshot rs
                            WHERE rs.publication_id = CAST(:publicationId AS uuid)
                              AND rs.game_id = %1$s)
                        ORDER BY lower(p.display_name), p.platform_id
                        LIMIT :summaryPlatformLimit
                    ) head
                ) summary_head
                """
                .formatted(gameColumn);
    }

    /** The summary of the current row; ids and names arrive aligned, in platform order. */
    public static Row read(ResultSet resultSet) throws SQLException {
        List<String> ids = strings(resultSet, "summary_platform_ids");
        List<String> names = strings(resultSet, "summary_platform_names");
        if (ids.size() != names.size()) {
            throw new IllegalStateException("Summary platform columns are misaligned");
        }
        return new Row(
                ids,
                names,
                resultSet.getInt("total_platforms"),
                resultSet.getObject("earliest_known_year", Integer.class),
                resultSet.getObject("latest_known_year", Integer.class));
    }

    private static List<String> strings(ResultSet resultSet, String column) throws SQLException {
        Array array = resultSet.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    /** One game's summary columns, before each read maps them to its own port shape. */
    public record Row(
            List<String> platformIds,
            List<String> platformNames,
            int totalPlatforms,
            Integer earliestKnownYear,
            Integer latestKnownYear) {}
}
