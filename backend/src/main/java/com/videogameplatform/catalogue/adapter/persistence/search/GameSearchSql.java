package com.videogameplatform.catalogue.adapter.persistence.search;

import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Production SQL and bound parameters, also used by the opt-in query-plan evidence. */
final class GameSearchSql {

    private GameSearchSql() {}

    /**
     * Ranks are ordered so a canonical-title match always precedes an alias-only match, and an
     * exact match precedes a prefix match, which precedes a plain all-token match.
     */
    private static final String CANDIDATE_CTE =
            """
            WITH candidate AS MATERIALIZED (
                SELECT gs.game_id,
                       CASE
                           WHEN gs.normalized_title = :normalizedQuery THEN 0
                           WHEN starts_with(gs.normalized_title, :normalizedQuery) THEN 1
                           ELSE 2
                       END AS match_rank,
                       NULL::varchar(300) AS matched_alias
                FROM catalogue.game_snapshot gs
                WHERE gs.publication_id = CAST(:publicationId AS uuid)
                  AND gs.title_search_vector
                      @@ to_tsquery('simple'::regconfig, :searchQuery)
                UNION ALL
                SELECT ga.game_id,
                       CASE
                           WHEN ga.normalized_alias = :normalizedQuery THEN 3
                           WHEN starts_with(ga.normalized_alias, :normalizedQuery) THEN 4
                           ELSE 5
                       END AS match_rank,
                       ga.alias
                FROM catalogue.game_alias ga
                WHERE ga.publication_id = CAST(:publicationId AS uuid)
                  AND ga.approval_status = 'approved'
                  AND ga.alias_search_vector
                      @@ to_tsquery('simple'::regconfig, :searchQuery)
            )
            """;

    static final String COUNT =
            CANDIDATE_CTE + "SELECT count(DISTINCT candidate.game_id) FROM candidate";

    /**
     * The bounded release context is joined only after {@code LIMIT}/{@code OFFSET}, so at most
     * {@code pageSize * releaseContextLimit} rows ever reach Java. The order is total: match
     * rank, normalized title, then the unique {@code game_id} of a one-row-per-game result.
     */
    static final String PAGE =
            CANDIDATE_CTE
                    + """
                    , ranked AS (
                        SELECT candidate.game_id,
                               min(candidate.match_rank) AS match_rank,
                               coalesce(
                                   min(candidate.matched_alias) FILTER (WHERE candidate.match_rank = 3),
                                   min(candidate.matched_alias) FILTER (WHERE candidate.match_rank = 4),
                                   min(candidate.matched_alias) FILTER (WHERE candidate.match_rank = 5))
                                   AS matched_alias
                        FROM candidate
                        GROUP BY candidate.game_id
                    ), page AS (
                        SELECT ranked.game_id,
                               ranked.match_rank,
                               ranked.matched_alias,
                               gs.slug,
                               gs.canonical_title,
                               gs.normalized_title,
                               gs.cover_reference,
                               gs.cover_source,
                               gs.cover_usage_mode,
                               gs.cover_alternative_text,
                               gs.cover_source_url
                        FROM ranked
                        JOIN catalogue.game_snapshot gs
                          ON gs.publication_id = CAST(:publicationId AS uuid)
                         AND gs.game_id = ranked.game_id
                        ORDER BY ranked.match_rank, gs.normalized_title, ranked.game_id
                        LIMIT :pageSize OFFSET :offset
                    )
                    SELECT page.game_id::text AS game_id,
                           page.matched_alias,
                           page.slug,
                           page.canonical_title,
                           page.cover_reference,
                           page.cover_source,
                           page.cover_usage_mode,
                           page.cover_alternative_text,
                           page.cover_source_url,
                           context.platform_id::text AS platform_id,
                           context.platform_name,
                           context.region_id::text AS region_id,
                           context.region_name,
                           context.date_precision,
                           context.exact_date,
                           context.release_year,
                           context.release_month,
                           context.release_quarter,
                           context.release_status,
                           context.last_synchronized_at
                    FROM page
                    LEFT JOIN LATERAL (
                        SELECT p.platform_id,
                               p.display_name AS platform_name,
                               r.region_id,
                               r.display_name AS region_name,
                               bounded.date_precision,
                               bounded.exact_date,
                               bounded.release_year,
                               bounded.release_month,
                               bounded.release_quarter,
                               bounded.release_status,
                               bounded.last_synchronized_at,
                               bounded.period_start,
                               bounded.release_id
                        FROM (
                            SELECT rs.release_id,
                                   rs.platform_id,
                                   rs.region_id,
                                   rs.date_precision,
                                   rs.exact_date,
                                   rs.release_year,
                                   rs.release_month,
                                   rs.release_quarter,
                                   rs.release_status,
                                   rs.last_synchronized_at,
                                   rs.period_start
                            FROM catalogue.release_snapshot rs
                            WHERE rs.publication_id = CAST(:publicationId AS uuid)
                              AND rs.game_id = page.game_id
                            ORDER BY rs.period_start ASC NULLS LAST, rs.release_id
                            LIMIT :releaseContextLimit
                        ) bounded
                        JOIN catalogue.platform p ON p.platform_id = bounded.platform_id
                        JOIN catalogue.region r ON r.region_id = bounded.region_id
                    ) context ON true
                    ORDER BY page.match_rank,
                             page.normalized_title,
                             page.game_id,
                             context.period_start ASC NULLS LAST,
                             context.release_id
                    """;

    private static final Pattern SAFE_TOKEN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+");

    static Map<String, Object> parameters(
            String publicationId, GameSearchReadPort.Criteria criteria) {
        return Map.of(
                "publicationId", publicationId,
                "normalizedQuery", criteria.normalizedQuery(),
                "searchQuery", searchQuery(criteria.tokens()),
                "pageSize", criteria.pagination().pageSize(),
                "offset", criteria.pagination().offset(),
                "releaseContextLimit", criteria.releaseContextLimit());
    }

    /**
     * Builds the all-token, prefix-matching {@code tsquery} text. Normalization already removed
     * every non-alphanumeric character, so no text-search operator can survive into the query;
     * the guard keeps that true even if the normalization rule is changed later.
     */
    private static String searchQuery(List<String> tokens) {
        for (String token : tokens) {
            if (!SAFE_TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException("Catalogue query token is not normalized");
            }
        }
        return tokens.stream().map(token -> token + ":*").collect(Collectors.joining(" & "));
    }
}
