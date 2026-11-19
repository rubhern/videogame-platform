package com.videogameplatform.catalogue.adapter.persistence.platformgames;

import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.Sort;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort.Criteria;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Production SQL and bound parameters of UC-019, also used by its query-plan evidence. */
final class PlatformGamesSql {

    private PlatformGamesSql() {}

    private static final Pattern SAFE_TOKEN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+");
    private static final String PLATFORMS_ARRAY = "CAST(:platformIds AS uuid[])";
    private static final String GENRES_ARRAY = "CAST(:genreIds AS uuid[])";

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

    private static final String MATCHING_GAME_IDS =
            """
            SELECT gs.game_id
            FROM catalogue.game_snapshot gs
            WHERE gs.publication_id = CAST(:publicationId AS uuid)
              AND gs.title_search_vector @@ to_tsquery('simple'::regconfig, :searchQuery)
            UNION
            SELECT ga.game_id
            FROM catalogue.game_alias ga
            WHERE ga.publication_id = CAST(:publicationId AS uuid)
              AND ga.approval_status = 'approved'
              AND ga.alias_search_vector @@ to_tsquery('simple'::regconfig, :searchQuery)
            """;

    /**
     * The minimum over the union of platforms is the minimum of their individual winners.
     * Compare that winner before applying any visitor filters, including the year.
     */
    private static final String FAMILY_WINNER =
            """
             AND NOT EXISTS (
                SELECT 1
                FROM catalogue.platform_game_presentation other
                WHERE other.publication_id = p.publication_id
                  AND other.game_id = p.game_id
                  AND other.platform_id <> p.platform_id
                  AND other.platform_id = ANY(CAST(:platformIds AS uuid[]))
                  AND other.valid_on @> CAST(:evaluatedOn AS date)
                  AND (other.occurrence, other.precedence,
                       coalesce(other.period_end, 'infinity'::date), other.region_code, other.release_id)
                      < (p.occurrence, p.precedence,
                         coalesce(p.period_end, 'infinity'::date), p.region_code, p.release_id)
            )
            """;

    /** Limits the ordered projection before joining the display payload. */
    static String read(Criteria criteria) {
        boolean searching = criteria.search() != null;
        boolean family = criteria.platformIds().size() > 1;
        String yearMatch = yearMatch(criteria);
        String genreMatch =
                criteria.genreIds().isEmpty() ? "true" : "p.genre_ids && " + GENRES_ARRAY;
        // A range-index scan followed by a full-scope sort was slower than walking the page's
        // B-tree order and checking the date bounds as each candidate is visited.
        String scope =
                """
                p.publication_id = CAST(:publicationId AS uuid)
                  AND coalesce(lower(p.valid_on), '-infinity'::date) <= CAST(:evaluatedOn AS date)
                  AND coalesce(upper(p.valid_on), 'infinity'::date) > CAST(:evaluatedOn AS date)
                """
                        + (searching
                                ? " AND p.game_id IN (SELECT game_id FROM matching_games)\n"
                                : "")
                        + releaseState(criteria)
                        + (family ? FAMILY_WINNER : "");
        String platformMatch =
                family
                        ? "p.platform_id = ANY(" + PLATFORMS_ARRAY + ")"
                        : "p.platform_id = (" + PLATFORMS_ARRAY + ")[1]";
        return "WITH "
                + (searching
                        ? "matching_games AS MATERIALIZED (\n" + MATCHING_GAME_IDS + "),\n"
                        : "")
                + """
                summary AS (
                    SELECT count(*) FILTER (WHERE %s AND %s) AS total_items,
                           min(p.known_year) FILTER (WHERE %s) AS earliest_year,
                           max(p.known_year) FILTER (WHERE %s) AS latest_year
                    FROM catalogue.platform_game_presentation p
                    WHERE %s AND %s
                ),
                genre_facet AS (
                    SELECT array_agg(g.code ORDER BY lower(g.display_name), g.code) AS genre_codes,
                           array_agg(g.display_name ORDER BY lower(g.display_name), g.code) AS genre_names
                    FROM (
                        SELECT term.genre_id
                        FROM catalogue.platform_game_presentation p
                        CROSS JOIN LATERAL unnest(p.genre_ids) term(genre_id)
                        WHERE %s AND %s AND %s
                        GROUP BY term.genre_id
                        UNION
                        SELECT unnest(CAST(:genreIds AS uuid[]))
                    ) facet
                    JOIN catalogue.genre g USING (genre_id)
                ),
                page AS MATERIALIZED (
                """
                        .formatted(
                                yearMatch,
                                genreMatch,
                                genreMatch,
                                genreMatch,
                                platformMatch,
                                scope,
                                platformMatch,
                                yearMatch,
                                scope)
                + page(criteria.sort(), family, scope, platformMatch, yearMatch, genreMatch)
                + """
                )
                SELECT summary.total_items, summary.earliest_year, summary.latest_year,
                       genre_facet.genre_codes, genre_facet.genre_names,
                       page.game_id::text AS game_id, gs.slug, gs.canonical_title,
                       gs.cover_reference, gs.cover_source, gs.cover_usage_mode,
                       gs.cover_alternative_text, gs.cover_source_url,
                       page.platform_id::text AS platform_id, platform.display_name AS platform_name,
                       rs.region_id::text AS region_id, region.display_name AS region_name,
                       rs.date_precision, rs.exact_date, rs.release_year, rs.release_month,
                       rs.release_quarter, rs.release_status,
                """
                + (searching
                        ? "       alias.alias AS matched_alias\n"
                        : "       NULL AS matched_alias\n")
                + """
                FROM summary
                CROSS JOIN genre_facet
                LEFT JOIN page ON true
                LEFT JOIN catalogue.game_snapshot gs USING (publication_id, game_id)
                LEFT JOIN catalogue.release_snapshot rs
                    ON rs.publication_id = page.publication_id AND rs.release_id = page.release_id
                LEFT JOIN catalogue.platform platform ON platform.platform_id = page.platform_id
                LEFT JOIN catalogue.region region ON region.region_id = rs.region_id
                """
                + (searching ? MATCHED_ALIAS : "")
                + "ORDER BY "
                + order(criteria.sort(), "page");
    }

    private static String page(
            Sort sort,
            boolean family,
            String scope,
            String platformMatch,
            String years,
            String genres) {
        String candidates =
                "SELECT p.* FROM catalogue.platform_game_presentation p\n"
                        + "WHERE "
                        + scope
                        + " AND "
                        + years
                        + " AND "
                        + genres
                        + " AND ";
        if (!family) {
            return candidates
                    + platformMatch
                    + "\nORDER BY "
                    + order(sort, "p")
                    + "\nLIMIT :pageSize OFFSET :offset\n";
        }
        // Each platform contributes at most the requested prefix to the family merge.
        return "SELECT candidate.* FROM unnest("
                + PLATFORMS_ARRAY
                + ") platform_scope(id)\n"
                + "CROSS JOIN LATERAL (\n"
                + candidates
                + "p.platform_id = platform_scope.id\n"
                + "ORDER BY "
                + order(sort, "p")
                + "\nLIMIT (:offset + :pageSize)\n"
                + ") candidate\nORDER BY "
                + order(sort, "candidate")
                + "\nLIMIT :pageSize OFFSET :offset\n";
    }

    /** The approved alias explaining a match the canonical title does not. */
    private static final String MATCHED_ALIAS =
            """
            LEFT JOIN LATERAL (
                SELECT ga.alias
                FROM catalogue.game_alias ga
                WHERE NOT (gs.title_search_vector @@ to_tsquery('simple'::regconfig, :searchQuery))
                  AND ga.publication_id = page.publication_id
                  AND ga.game_id = page.game_id
                  AND ga.approval_status = 'approved'
                  AND ga.alias_search_vector @@ to_tsquery('simple'::regconfig, :searchQuery)
                ORDER BY CASE
                             WHEN ga.normalized_alias = :normalizedQuery THEN 0
                             WHEN starts_with(ga.normalized_alias, :normalizedQuery) THEN 1
                             ELSE 2
                         END, ga.normalized_alias, ga.alias
                LIMIT 1
            ) alias ON true
            """;

    private static String releaseState(Criteria criteria) {
        if (criteria.releaseState() == null) {
            return "";
        }
        return switch (criteria.releaseState()) {
            case RELEASED -> " AND p.occurrence = 0\n";
            case UPCOMING -> " AND p.occurrence > 0\n";
        };
    }

    private static String yearMatch(Criteria criteria) {
        List<String> predicates = new ArrayList<>(2);
        if (criteria.fromYear() != null) {
            predicates.add("p.known_year >= :fromYear");
        }
        if (criteria.toYear() != null) {
            predicates.add("p.known_year <= :toYear");
        }
        return predicates.isEmpty() ? "true" : String.join(" AND ", predicates);
    }

    private static String datesFirst(String alias, String direction) {
        return alias
                + ".period_end "
                + direction
                + " NULLS LAST, "
                + alias
                + ".precision_rank, "
                + alias
                + ".normalized_title, "
                + alias
                + ".game_id";
    }

    /** Each order matches its projection index and ends in the unique game. */
    static String order(Sort sort, String alias) {
        return switch (sort) {
            case RELEASE_DATE_DESC -> datesFirst(alias, "DESC");
            case RELEASE_DATE_ASC -> datesFirst(alias, "ASC");
            case TITLE_ASC -> alias + ".normalized_title ASC, " + alias + ".game_id";
            case TITLE_DESC -> alias + ".normalized_title DESC, " + alias + ".game_id";
            case COMMUNITY_SCORE ->
                    alias
                            + ".community_mean DESC NULLS LAST, "
                            + alias
                            + ".community_rating_count DESC NULLS LAST, "
                            + datesFirst(alias, "DESC");
            case EXTERNAL_PLAYER_SCORE ->
                    alias
                            + ".player_score DESC NULLS LAST, "
                            + alias
                            + ".player_score_count DESC NULLS LAST, "
                            + datesFirst(alias, "DESC");
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
