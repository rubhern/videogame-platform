package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort;

/**
 * The games of a release range and their order as SQL, shared by the release explorer and Destacados
 * so that, for one range and platforms, Destacados is exactly the head of the explorer's attention
 * order (FEAT-001, FEAT-002, ATT-005).
 *
 * <p>{@code filtered_release} holds the releases matching the given predicates, {@code
 * presented_release} keeps the one presented release of each game and platform among them, and
 * {@code game_top} positions each game by its first presented release. Only presented releases
 * position, group and fill a page; the others stay stored and in game details.
 */
final class ReleaseRangeGameSql {

    private static final String GAME_SNAPSHOT_LATERAL =
            """
            JOIN LATERAL (
                SELECT snapshot.slug,
                       snapshot.canonical_title,
                       snapshot.cover_reference,
                       snapshot.cover_source,
                       snapshot.cover_usage_mode,
                       snapshot.cover_alternative_text,
                       snapshot.cover_source_url
                FROM catalogue.game_snapshot snapshot
                WHERE snapshot.publication_id = fr.publication_id
                  AND snapshot.game_id = fr.game_id
                LIMIT 1
            ) gs ON true
            """;

    private ReleaseRangeGameSql() {}

    /**
     * Opens the query with the three shared common table expressions, without a trailing comma.
     *
     * @param where the trusted predicates over {@code release_snapshot rs}, starting with WHERE
     */
    static String presentedGames(String where) {
        return "WITH filtered_release AS MATERIALIZED (SELECT * FROM catalogue.release_snapshot rs"
                + where
                + "),\n"
                + "presented_release AS MATERIALIZED (\n"
                + "    SELECT DISTINCT ON (fr.game_id, fr.platform_id) fr.*\n"
                + "    FROM filtered_release fr\n"
                + "    JOIN catalogue.region release_region"
                + " ON release_region.region_id = fr.region_id\n"
                + "    ORDER BY fr.game_id, fr.platform_id, "
                + ReleasePresentationOrder.of("fr", "release_region", dateOrder("fr"))
                + "\n),\n"
                + "game_top AS (\n"
                + "    SELECT DISTINCT ON (fr.game_id)\n"
                + "        fr.game_id, fr.period_end, fr.period_start, fr.date_precision,\n"
                + "        gs.slug, gs.canonical_title, gs.cover_reference, gs.cover_source,\n"
                + "        gs.cover_usage_mode, gs.cover_alternative_text, gs.cover_source_url,\n"
                // Whether a launch release of the game is in the range (ATT-007); one hashed
                // lookup over the range's releases, never a probe per game.
                + "        fr.game_id IN (SELECT launch.game_id FROM filtered_release launch"
                + " WHERE launch.release_stage IN "
                + ReleaseAttentionSql.LAUNCH_STAGES
                + ") AS full_launch\n"
                + "    FROM presented_release fr\n"
                + GAME_SNAPSHOT_LATERAL
                + "    ORDER BY fr.game_id, "
                + releaseOrder("fr")
                + "\n)";
    }

    /**
     * The ascending date order: the last day of the known period, which is the day itself for an
     * exact date, then an exact day before a month, quarter or year ending the same day. It never
     * places a partial date on an invented day.
     */
    private static String dateOrder(String alias) {
        return alias + ".period_end ASC, " + ReleasePresentationOrder.precisionRank(alias);
    }

    /** Ordering of the releases inside one game; ends in the unique release id. */
    static String releaseOrder(String alias) {
        return dateOrder(alias) + ", " + alias + ".release_id";
    }

    /**
     * Ordering of the games, each positioned by its first presented release; ends in the unique
     * game id. Attention ({@link ReleaseAttentionSql}) and rating lead when chosen, and a game
     * without the signal follows every game with one rather than counting as zero.
     */
    static String gameOrder(Sort sort, String alias) {
        String dateOrder =
                dateOrder(alias) + ", lower(" + alias + ".canonical_title), " + alias + ".game_id";
        return switch (sort) {
            case RELEASE_DATE -> dateOrder;
            case HYPE -> alias + ".attention DESC NULLS LAST, " + dateOrder;
            case RATING ->
                    alias
                            + ".community_mean DESC NULLS LAST, "
                            + alias
                            + ".community_rating_count DESC NULLS LAST, "
                            + dateOrder;
            case EXTERNAL_PLAYER_SCORE ->
                    alias
                            + ".player_score DESC NULLS LAST, "
                            + alias
                            + ".player_score_count DESC NULLS LAST, "
                            + dateOrder;
        };
    }
}
