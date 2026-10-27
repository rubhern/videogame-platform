package com.videogameplatform.catalogue.adapter.persistence.rankings;

import com.videogameplatform.catalogue.adapter.persistence.CompactReleaseSummarySql;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort.Criteria;
import java.util.Map;

/** Production SQL and bound parameters of UC-018, also used by the opt-in query-plan evidence. */
final class GameRankingSql {

    private GameRankingSql() {}

    /**
     * Each source's usable scores, one row per game: the order's key (the unrounded mean) and
     * count, plus what the game's reading shows. Only Catalogue-owned tables are read: the stored
     * external player score (XSC-002) and the community projection Ratings maintains through a
     * Catalogue contract (ADR-0029, ADR-0034). A game without a usable score, or whose score rests
     * on fewer ratings than the source's minimum, has no row, so it is never ranked and never
     * counts as zero.
     */
    private static String scored(Source source) {
        return switch (source) {
            case IGDB_PLAYERS ->
                    """
                    SELECT es.game_id, es.score AS rank_score, es.score_count AS rank_count,
                           NULL::numeric AS shown_mean, es.source_url, es.observed_at,
                           es.provider_record_updated_at
                    FROM catalogue.game_external_score es
                    WHERE es.source_name = 'IGDB' AND es.score_kind = 'player'
                      AND es.score IS NOT NULL AND es.score_count >= :minimumRatings
                    """;
            case GAMEOMETRO_COMMUNITY ->
                    """
                    SELECT cs.game_id, cs.exact_mean AS rank_score, cs.rating_count AS rank_count,
                           cs.mean AS shown_mean, NULL::varchar AS source_url,
                           NULL::timestamptz AS observed_at,
                           NULL::timestamptz AS provider_record_updated_at
                    FROM catalogue.game_community_score cs
                    WHERE cs.exact_mean IS NOT NULL AND cs.rating_count >= :minimumRatings
                    """;
        };
    }

    /**
     * Global rating eligibility (domain model, "Rating policies"; REL-007, REL-012), so a ranked
     * game is one a player may rate: a current release of the game that is neither cancelled,
     * delayed nor pending review and has effectively occurred at the trusted evaluation date — an
     * exact day from that day on, a month, quarter or year once it has ended — or, without a known
     * date, explicit verified released evidence. Release stage does not participate, as in that
     * policy. The release snapshot's foreign key to the game snapshot makes it a current game too.
     */
    private static final String ELIGIBLE =
            """
            EXISTS (
                SELECT 1 FROM catalogue.release_snapshot rs
                WHERE rs.publication_id = CAST(:publicationId AS uuid)
                  AND rs.game_id = scored.game_id
                  AND rs.release_status NOT IN ('cancelled', 'delayed')
                  AND rs.review_status = 'not_required'
                  AND ((rs.date_precision = 'day'
                          AND rs.exact_date <= CAST(:evaluatedOn AS date))
                       OR (rs.date_precision IN ('month', 'quarter', 'year')
                          AND rs.period_end < CAST(:evaluatedOn AS date))
                       OR (rs.date_precision = 'unknown'
                          AND rs.release_status = 'released'
                          AND rs.verification_level = 'verified')))
            """;

    /** The unrounded mean, then the number of ratings behind it, then the unique game. */
    private static String order(String alias) {
        return alias + ".rank_score DESC, " + alias + ".rank_count DESC, " + alias + ".game_id";
    }

    static String count(Source source) {
        return "WITH scored AS (\n"
                + scored(source)
                + ")\nSELECT count(*) FROM scored WHERE "
                + ELIGIBLE;
    }

    /**
     * The page is cut from the source's scores in their order before anything is joined, so the
     * partial rank index can feed it and stop early; the game snapshot, its compact release summary
     * and the genres are read for those games only.
     */
    static String page(Source source) {
        return "WITH scored AS (\n"
                + scored(source)
                + "), page AS (\n"
                + "    SELECT scored.* FROM scored WHERE "
                + ELIGIBLE
                + "    ORDER BY "
                + order("scored")
                + "\n    LIMIT :pageSize OFFSET :offset\n)\n"
                + """
                SELECT page.game_id::text AS game_id,
                       page.rank_score,
                       page.rank_count,
                       page.shown_mean,
                       page.source_url,
                       page.observed_at,
                       page.provider_record_updated_at,
                       gs.slug,
                       gs.canonical_title,
                       gs.cover_reference,
                       gs.cover_source,
                       gs.cover_usage_mode,
                       gs.cover_alternative_text,
                       gs.cover_source_url,
                """
                + CompactReleaseSummarySql.COLUMNS
                + "\nFROM page\n"
                + "JOIN catalogue.game_snapshot gs\n"
                + "  ON gs.publication_id = CAST(:publicationId AS uuid)"
                + " AND gs.game_id = page.game_id\n"
                + CompactReleaseSummarySql.laterals("page.game_id")
                + "ORDER BY "
                + order("page");
    }

    static Map<String, Object> parameters(String publicationId, Criteria criteria) {
        return Map.of(
                "publicationId", publicationId,
                "minimumRatings", criteria.source().minimumRatings(),
                "evaluatedOn", criteria.evaluatedOn(),
                "pageSize", criteria.pagination().pageSize(),
                "offset", criteria.pagination().offset(),
                "summaryPlatformLimit", criteria.summaryPlatformLimit());
    }
}
