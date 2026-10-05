package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** Indexed term reads only for the already bounded game page, using the detail metadata model. */
public final class CatalogueGenresReader {
    private CatalogueGenresReader() {}

    static final String SQL =
            """
        SELECT g.game_id::text AS game_id, terms.code, terms.display_name
        FROM catalogue.game_snapshot g
        JOIN LATERAL (
            SELECT t.code, t.display_name FROM catalogue.game_genre link
            JOIN catalogue.genre t ON t.genre_id=link.genre_id
            WHERE link.game_id=g.game_id
            ORDER BY lower(t.display_name), t.code LIMIT :genreLimit
        ) terms ON true
        WHERE g.publication_id=CAST(:publicationId AS uuid) AND g.game_id IN (:gameIds)
        ORDER BY g.game_id, lower(terms.display_name), terms.code
        """;

    public static Map<String, List<GameDetailsResult.Term>> read(
            NamedParameterJdbcOperations jdbc,
            String publicationId,
            List<String> gameIds,
            int limit) {
        if (gameIds.size() > 100 || limit < 1 || limit > 51)
            throw new IllegalArgumentException("Genre read exceeds its bound");
        if (gameIds.isEmpty()) return Map.of();
        Map<String, List<GameDetailsResult.Term>> result = new LinkedHashMap<>();
        jdbc.query(
                SQL,
                Map.of(
                        "publicationId",
                        publicationId,
                        "gameIds",
                        gameIds.stream().map(UUID::fromString).toList(),
                        "genreLimit",
                        limit),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs ->
                                result.computeIfAbsent(
                                                rs.getString("game_id"),
                                                ignored -> new ArrayList<>())
                                        .add(
                                                new GameDetailsResult.Term(
                                                        rs.getString("code"),
                                                        rs.getString("display_name"))));
        return result;
    }
}
