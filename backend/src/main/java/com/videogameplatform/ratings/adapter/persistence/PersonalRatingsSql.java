package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.port.PersonalRatingsReadPort.Criteria;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The production statements are also the source for reproducible query-plan evidence. */
final class PersonalRatingsSql {
    private PersonalRatingsSql() {}

    static String count(Criteria criteria) {
        return "SELECT count(*) " + scope(criteria);
    }

    static String page(Criteria criteria) {
        String key =
                switch (criteria.sort()) {
                    case UPDATED -> "r.updated_at";
                    case TITLE -> "g.normalized_title COLLATE \"C\"";
                    case VALUE -> "r.value";
                };
        return "SELECT r.*, g.slug, g.canonical_title, g.cover_kind, g.cover_reference, "
                + "g.cover_alternative_text, g.cover_attribution, g.cover_source_url "
                + scope(criteria)
                + " ORDER BY "
                + key
                + (criteria.descending() ? " DESC" : " ASC")
                + ", r.game_id ASC LIMIT :limit OFFSET :offset";
    }

    private static String scope(Criteria criteria) {
        // The optimization fences are deliberate: EXPLAIN showed PostgreSQL otherwise scanning
        // all listings and hashing all matching aliases before joining the user's rated set.
        // Correlated index probes keep both title and alias work behind user scoping.
        String scope =
                """
                FROM ratings.rating r
                JOIN LATERAL (
                    SELECT listing.* FROM ratings.game_listing listing
                    WHERE listing.game_id = r.game_id OFFSET 0
                ) g ON true
                WHERE r.user_id = :user
                """;
        if (!criteria.tokens().isEmpty()) {
            scope +=
                    """
                AND (g.title_search_vector @@ to_tsquery('simple'::regconfig, :query)
                    OR EXISTS (SELECT 1 FROM ratings.game_listing_alias a
                        WHERE a.game_id = r.game_id AND a.search_vector @@ to_tsquery('simple'::regconfig, :query)
                        OFFSET 0))
                """;
        }
        return scope;
    }

    static Map<String, Object> parameters(String userId, Criteria criteria) {
        if (criteria.tokens().stream()
                .anyMatch(token -> !token.matches("[\\p{IsAlphabetic}\\p{IsDigit}]+"))) {
            throw new IllegalArgumentException("Search tokens must be normalized");
        }
        return Map.of(
                "user",
                UUID.fromString(userId),
                "query",
                criteria.tokens().stream()
                        .map(token -> token + ":*")
                        .collect(Collectors.joining(" & ")),
                "limit",
                criteria.pageSize(),
                "offset",
                criteria.offset());
    }
}
