package com.videogameplatform.catalogue.adapter.persistence.platformgames;

import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.Sort;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort.Criteria;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Production SQL and bound parameters of UC-019, also used by the opt-in query-plan evidence.
 *
 * <p>Every statement starts from the releases of the platform scope and keeps one presented
 * release per game before any filter, count, facet or page, so a game with many releases, regions
 * or platforms of the scope is one row and never multiplies. Only trusted fragments are
 * concatenated; every visitor value is a bound parameter.
 */
final class PlatformGamesSql {

    private PlatformGamesSql() {}

    private static final Pattern SAFE_TOKEN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+");

    /**
     * Occurrence class at the trusted evaluation date (REL-011, REL-012), the same rule as {@code
     * EffectiveReleaseStatusPolicy}: 0 has effectively occurred — an exact day from that day on, a
     * month, quarter or year once it has ended, an unknown date only with explicit released
     * evidence — 1 is expected on a known period, 2 is delayed or undated. Delayed evidence never
     * occurs.
     */
    private static final String OCCURRENCE =
            """
            CASE
                WHEN rs.release_status <> 'delayed'
                     AND ((rs.date_precision = 'day'
                             AND rs.exact_date <= CAST(:evaluatedOn AS date))
                          OR (rs.date_precision IN ('month', 'quarter', 'year')
                             AND rs.period_end < CAST(:evaluatedOn AS date))
                          OR (rs.date_precision = 'unknown'
                             AND rs.release_status = 'released')) THEN 0
                WHEN rs.release_status <> 'delayed' AND rs.date_precision <> 'unknown' THEN 1
                ELSE 2
            END""";

    /**
     * The scope's releases, one presented per game: its occurrence class first, so a game that
     * is out is always presented by a release that is out, then the presentation precedence of
     * discovery and game details (REL-013) ending in the earliest date. Cancelled releases never
     * associate a game with the platform.
     */
    private static final String PRESENTED =
            "WITH presented AS MATERIALIZED (\n"
                    + "    SELECT DISTINCT ON (pr.game_id) pr.*,\n"
                    + "           extract(YEAR FROM pr.period_start)::integer AS known_year\n"
                    + "    FROM (\n"
                    + "        SELECT rs.game_id, rs.release_id, rs.platform_id, rs.region_id,\n"
                    + "               rs.date_precision, rs.exact_date, rs.release_year,\n"
                    + "               rs.release_month, rs.release_quarter, rs.release_status,\n"
                    + "               rs.release_stage, rs.review_status, rs.verification_level,\n"
                    + "               rs.period_start, rs.period_end,\n"
                    + OCCURRENCE
                    + " AS occurrence\n"
                    + "        FROM catalogue.release_snapshot rs\n"
                    + "        WHERE rs.publication_id = CAST(:publicationId AS uuid)\n"
                    + "          AND rs.platform_id = ANY(CAST(:platformIds AS uuid[]))\n"
                    + "          AND rs.release_status <> 'cancelled'\n"
                    + "    ) pr\n"
                    + "    JOIN catalogue.region release_region"
                    + " ON release_region.region_id = pr.region_id\n"
                    + "    ORDER BY pr.game_id, pr.occurrence, "
                    + ReleasePresentationOrder.of("pr", "release_region", "pr.period_end")
                    + "\n)";

    static final String PLATFORMS =
            """
            SELECT p.platform_id::text AS platform_id, p.display_name
            FROM catalogue.platform p
            WHERE p.platform_id::text IN (:platformIds)
            ORDER BY lower(p.display_name), p.platform_id
            """;

    static final String GENRES =
            """
            SELECT g.genre_id::text AS genre_id, g.code
            FROM catalogue.genre g
            WHERE g.code IN (:genreCodes)
            ORDER BY g.code
            """;

    /**
     * One statement derives the presented releases once and answers everything from them: the
     * games matching the search and release state form {@code base}, each flagged by whether it
     * matches the year range and the genres. The total counts the games matching both; the known
     * years span those matching the genres; the genre facet gathers the genres of those matching
     * the years, always keeping a selected genre; the page orders and cuts those matching both.
     * Every output row repeats the total and the facets; with no game on the page one row carries
     * them alone.
     */
    static String read(Criteria criteria) {
        boolean searching = criteria.search() != null;
        return PRESENTED
                + ",\nbase AS MATERIALIZED (\n"
                + base(criteria)
                + "),\n"
                + """
                summary AS (
                    SELECT count(*) FILTER (WHERE base.year_match AND base.genre_match)
                               AS total_items,
                           min(base.known_year) FILTER (WHERE base.genre_match) AS earliest_year,
                           max(base.known_year) FILTER (WHERE base.genre_match) AS latest_year
                    FROM base
                ),
                genre_facet AS (
                    SELECT array_agg(g.code ORDER BY lower(g.display_name), g.code) AS genre_codes,
                           array_agg(g.display_name ORDER BY lower(g.display_name), g.code)
                               AS genre_names
                    FROM catalogue.genre g
                    WHERE g.genre_id IN (
                            SELECT gg.genre_id
                            FROM base
                            JOIN catalogue.game_genre gg ON gg.game_id = base.game_id
                            WHERE base.year_match)
                       OR g.genre_id = ANY(CAST(:genreIds AS uuid[]))
                ),
                page AS (
                    SELECT c.*
                    FROM (
                        SELECT base.*
                """
                + scoreColumns(criteria.sort())
                + "\n                        FROM base\n"
                + scoreJoins(criteria.sort())
                + """
                        WHERE base.year_match AND base.genre_match
                    ) c
                    ORDER BY\s"""
                + order(criteria.sort(), "c")
                + """

                    LIMIT :pageSize OFFSET :offset
                )
                SELECT summary.total_items,
                       summary.earliest_year,
                       summary.latest_year,
                       genre_facet.genre_codes,
                       genre_facet.genre_names,
                       page.game_id::text AS game_id,
                       page.slug,
                       page.canonical_title,
                       page.cover_reference,
                       page.cover_source,
                       page.cover_usage_mode,
                       page.cover_alternative_text,
                       page.cover_source_url,
                       page.platform_id::text AS platform_id,
                       platform.display_name AS platform_name,
                       page.region_id::text AS region_id,
                       region.display_name AS region_name,
                       page.date_precision,
                       page.exact_date,
                       page.release_year,
                       page.release_month,
                       page.release_quarter,
                       page.release_status,
                """
                + (searching
                        ? "       alias.alias AS matched_alias\n"
                        : "       NULL AS matched_alias\n")
                + """
                FROM summary
                CROSS JOIN genre_facet
                LEFT JOIN page ON true
                LEFT JOIN catalogue.platform platform ON platform.platform_id = page.platform_id
                LEFT JOIN catalogue.region region ON region.region_id = page.region_id
                """
                + (searching ? MATCHED_ALIAS : "")
                + "ORDER BY "
                + order(criteria.sort(), "page");
    }

    /**
     * The best-ranked approved alias explaining a match the canonical title does not: exact,
     * then prefix, then any all-token match, as search ranks aliases.
     */
    private static final String MATCHED_ALIAS =
            """
            LEFT JOIN LATERAL (
                SELECT ga.alias
                FROM catalogue.game_alias ga
                WHERE NOT page.title_match
                  AND ga.publication_id = CAST(:publicationId AS uuid)
                  AND ga.game_id = page.game_id
                  AND ga.approval_status = 'approved'
                  AND ga.alias_search_vector @@ to_tsquery('simple'::regconfig, :searchQuery)
                ORDER BY CASE
                             WHEN ga.normalized_alias = :normalizedQuery THEN 0
                             WHEN starts_with(ga.normalized_alias, :normalizedQuery) THEN 1
                             ELSE 2
                         END,
                         ga.normalized_alias,
                         ga.alias
                LIMIT 1
            ) alias ON true
            """;

    /**
     * The presented releases joined to their game and narrowed by the search and the release
     * state, with the year range and the genres as flags, so the facets that leave out their own
     * dimension read the same rows. Without a year range or genres the flag is simply true.
     */
    private static String base(Criteria criteria) {
        boolean searching = criteria.search() != null;
        List<String> years = yearPredicates(criteria);
        StringBuilder sql =
                new StringBuilder(
                        """
                            SELECT p.*, gs.slug, gs.canonical_title, gs.normalized_title,
                                   gs.cover_reference, gs.cover_source, gs.cover_usage_mode,
                                   gs.cover_alternative_text, gs.cover_source_url,
                        """);
        sql.append("           ")
                .append(years.isEmpty() ? "true" : String.join(" AND ", years))
                .append(" AS year_match,\n           ")
                .append(
                        criteria.genreIds().isEmpty()
                                ? "true"
                                : "EXISTS (SELECT 1 FROM catalogue.game_genre gg"
                                        + " WHERE gg.game_id = p.game_id"
                                        + " AND gg.genre_id = ANY(CAST(:genreIds AS uuid[])))")
                .append(" AS genre_match,\n           ")
                .append(
                        searching
                                ? "gs.title_search_vector"
                                        + " @@ to_tsquery('simple'::regconfig, :searchQuery)"
                                : "true")
                .append(" AS title_match\n")
                .append("    FROM presented p\n")
                .append("    JOIN catalogue.game_snapshot gs\n")
                .append("      ON gs.publication_id = CAST(:publicationId AS uuid)")
                .append(" AND gs.game_id = p.game_id\n");
        List<String> predicates = new ArrayList<>();
        if (searching) {
            predicates.add(
                    "(gs.title_search_vector @@ to_tsquery('simple'::regconfig, :searchQuery)"
                            + " OR EXISTS (SELECT 1 FROM catalogue.game_alias ga"
                            + " WHERE ga.publication_id = CAST(:publicationId AS uuid)"
                            + " AND ga.game_id = p.game_id AND ga.approval_status = 'approved'"
                            + " AND ga.alias_search_vector"
                            + " @@ to_tsquery('simple'::regconfig, :searchQuery)))");
        }
        if (criteria.releaseState() != null) {
            predicates.add(
                    switch (criteria.releaseState()) {
                        case RELEASED -> "p.occurrence = 0";
                        case UPCOMING -> "p.occurrence > 0";
                    });
        }
        if (!predicates.isEmpty()) {
            sql.append("    WHERE ").append(String.join("\n      AND ", predicates)).append('\n');
        }
        return sql.toString();
    }

    private static List<String> yearPredicates(Criteria criteria) {
        List<String> predicates = new ArrayList<>(2);
        if (criteria.fromYear() != null) {
            predicates.add("p.known_year >= :fromYear");
        }
        if (criteria.toYear() != null) {
            predicates.add("p.known_year <= :toYear");
        }
        return predicates;
    }

    /**
     * The signals a score order needs, from Catalogue-owned tables only: the community projection
     * Ratings maintains through a Catalogue contract (ADR-0029) and the stored external player
     * score at the one decimal discovery shows.
     */
    private static String scoreColumns(Sort sort) {
        return switch (sort) {
            case COMMUNITY_SCORE ->
                    ",\n               cs.mean AS community_mean,"
                            + " cs.rating_count AS community_rating_count";
            case EXTERNAL_PLAYER_SCORE ->
                    ",\n               round(es.score / 10, 1) AS player_score,"
                            + " es.score_count AS player_score_count";
            default -> "";
        };
    }

    private static String scoreJoins(Sort sort) {
        return switch (sort) {
            case COMMUNITY_SCORE ->
                    "        LEFT JOIN catalogue.game_community_score cs"
                            + " ON cs.game_id = base.game_id\n";
            case EXTERNAL_PLAYER_SCORE ->
                    "        LEFT JOIN catalogue.game_external_score es"
                            + " ON es.game_id = base.game_id AND es.source_name = 'IGDB'"
                            + " AND es.score_kind = 'player' AND es.score IS NOT NULL\n";
            default -> "";
        };
    }

    /**
     * The latest presented date first: the last day of its known period, an exact day before a
     * period ending the same day, an unknown date after every known one.
     */
    private static String latestFirst(String alias) {
        return alias
                + ".period_end DESC NULLS LAST, "
                + ReleasePresentationOrder.precisionRank(alias)
                + ", "
                + alias
                + ".normalized_title, "
                + alias
                + ".game_id";
    }

    /** Every order ends in the unique game; a missing score follows every present one. */
    static String order(Sort sort, String alias) {
        return switch (sort) {
            case RELEASE_DATE_DESC -> latestFirst(alias);
            case RELEASE_DATE_ASC ->
                    alias
                            + ".period_end ASC NULLS LAST, "
                            + ReleasePresentationOrder.precisionRank(alias)
                            + ", "
                            + alias
                            + ".normalized_title, "
                            + alias
                            + ".game_id";
            case TITLE_ASC -> alias + ".normalized_title ASC, " + alias + ".game_id";
            case TITLE_DESC -> alias + ".normalized_title DESC, " + alias + ".game_id";
            case COMMUNITY_SCORE ->
                    alias
                            + ".community_mean DESC NULLS LAST, "
                            + alias
                            + ".community_rating_count DESC NULLS LAST, "
                            + latestFirst(alias);
            case EXTERNAL_PLAYER_SCORE ->
                    alias
                            + ".player_score DESC NULLS LAST, "
                            + alias
                            + ".player_score_count DESC NULLS LAST, "
                            + latestFirst(alias);
        };
    }

    /**
     * @param platformIds the requested platforms, already validated to exist
     * @param genreIds the internal identifiers of the requested genres, already validated
     */
    static Map<String, Object> parameters(
            String publicationId,
            Criteria criteria,
            List<String> platformIds,
            List<String> genreIds) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("publicationId", publicationId);
        parameters.put("platformIds", arrayLiteral(platformIds));
        parameters.put("genreIds", arrayLiteral(genreIds));
        parameters.put("evaluatedOn", criteria.evaluatedOn());
        if (criteria.fromYear() != null) {
            parameters.put("fromYear", criteria.fromYear());
        }
        if (criteria.toYear() != null) {
            parameters.put("toYear", criteria.toYear());
        }
        if (criteria.search() != null) {
            parameters.put("normalizedQuery", criteria.search().normalizedQuery());
            parameters.put("searchQuery", searchQuery(criteria.search().tokens()));
        }
        parameters.put("pageSize", criteria.pagination().pageSize());
        parameters.put("offset", criteria.pagination().offset());
        return parameters;
    }

    /** A PostgreSQL array literal of identifiers that exist in the taxonomy, so they are uuids. */
    private static String arrayLiteral(List<String> ids) {
        return ids.stream().collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * The all-token, prefix-matching {@code tsquery} text of the search rule. Normalization
     * removed every non-alphanumeric character; the guard keeps any operator out.
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
