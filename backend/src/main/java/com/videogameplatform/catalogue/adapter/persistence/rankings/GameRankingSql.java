package com.videogameplatform.catalogue.adapter.persistence.rankings;

import com.videogameplatform.catalogue.adapter.persistence.CompactReleaseSummarySql;
import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort.Criteria;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private static final String ELIGIBLE_RELEASE =
            """
                  rs.release_status NOT IN ('cancelled', 'delayed')
                  AND rs.review_status = 'not_required'
                  AND ((rs.date_precision = 'day'
                          AND rs.exact_date <= CAST(:evaluatedOn AS date))
                       OR (rs.date_precision IN ('month', 'quarter', 'year')
                          AND rs.period_end < CAST(:evaluatedOn AS date))
                       OR (rs.date_precision = 'unknown'
                          AND rs.release_status = 'released'
                          AND rs.verification_level = 'verified'))
            """;

    /**
     * A platform scope (RNK-005) asks the same of a release on one of its platforms, so a game
     * qualifies on a platform only once it has effectively come out there, whatever it did
     * elsewhere before; the scope is part of the candidate set, before order, count and page.
     */
    private static final String ON_SCOPE =
            "                  AND rs.platform_id = ANY(CAST(:platformIds AS uuid[]))\n";

    private static String eligible(boolean scoped) {
        return """
                EXISTS (
                    SELECT 1 FROM catalogue.release_snapshot rs
                    WHERE rs.publication_id = CAST(:publicationId AS uuid)
                      AND rs.game_id = scored.game_id
                      AND"""
                + ELIGIBLE_RELEASE
                + (scoped ? ON_SCOPE : "")
                + ")\n";
    }

    /**
     * The release that presents a ranked game on the scope: among its eligible scope releases, the
     * presentation precedence of discovery and game details (REL-013), ending in the earliest date.
     * It is read for the page's games only.
     */
    private static final String PRESENTED_RELEASE =
            """
            CROSS JOIN LATERAL (
                SELECT rs.platform_id, platform.display_name AS platform_name,
                       rs.region_id, release_region.display_name AS region_name,
                       rs.date_precision, rs.exact_date, rs.release_year, rs.release_month,
                       rs.release_quarter, rs.release_status
                FROM catalogue.release_snapshot rs
                JOIN catalogue.region release_region ON release_region.region_id = rs.region_id
                JOIN catalogue.platform platform ON platform.platform_id = rs.platform_id
                WHERE rs.publication_id = CAST(:publicationId AS uuid)
                  AND rs.game_id = page.game_id
                  AND"""
                    + ELIGIBLE_RELEASE
                    + ON_SCOPE
                    + "    ORDER BY "
                    + ReleasePresentationOrder.of("rs", "release_region", "rs.period_end")
                    + "\n    LIMIT 1\n) presented\n";

    /** The unrounded mean, then the number of ratings behind it, then the unique game. */
    private static String order(String alias) {
        return alias + ".rank_score DESC, " + alias + ".rank_count DESC, " + alias + ".game_id";
    }

    static String count(Source source, boolean scoped) {
        return "WITH scored AS (\n"
                + scored(source)
                + ")\nSELECT count(*) FROM scored WHERE "
                + eligible(scoped);
    }

    /**
     * The page is cut from the source's scores in their order before anything is joined, so the
     * partial rank index can feed it and stop early; the game snapshot, its compact release summary,
     * the presented scope release and the genres are read for those games only.
     */
    static String page(Source source, boolean scoped) {
        return "WITH scored AS (\n"
                + scored(source)
                + "), page AS (\n"
                + "    SELECT scored.* FROM scored WHERE "
                + eligible(scoped)
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
                + (scoped ? SCOPE_COLUMNS : "")
                + CompactReleaseSummarySql.COLUMNS
                + "\nFROM page\n"
                + "JOIN catalogue.game_snapshot gs\n"
                + "  ON gs.publication_id = CAST(:publicationId AS uuid)"
                + " AND gs.game_id = page.game_id\n"
                + (scoped ? PRESENTED_RELEASE : "")
                + CompactReleaseSummarySql.laterals("page.game_id")
                + "ORDER BY "
                + order("page");
    }

    private static final String SCOPE_COLUMNS =
            """
                   presented.platform_id::text AS platform_id,
                   presented.platform_name,
                   presented.region_id::text AS region_id,
                   presented.region_name,
                   presented.date_precision,
                   presented.exact_date,
                   presented.release_year,
                   presented.release_month,
                   presented.release_quarter,
                   presented.release_status,
            """;

    /**
     * @param platformIds the scope's platforms, already validated to exist, so they are uuids
     */
    static Map<String, Object> parameters(
            String publicationId, Criteria criteria, List<String> platformIds) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("publicationId", publicationId);
        parameters.put("minimumRatings", criteria.source().minimumRatings());
        parameters.put("evaluatedOn", criteria.evaluatedOn());
        parameters.put("pageSize", criteria.pagination().pageSize());
        parameters.put("offset", criteria.pagination().offset());
        parameters.put("summaryPlatformLimit", criteria.summaryPlatformLimit());
        if (!platformIds.isEmpty()) {
            parameters.put(
                    "platformIds", platformIds.stream().collect(Collectors.joining(",", "{", "}")));
        }
        return parameters;
    }
}
