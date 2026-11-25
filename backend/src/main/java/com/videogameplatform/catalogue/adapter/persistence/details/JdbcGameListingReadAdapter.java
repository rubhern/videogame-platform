package com.videogameplatform.catalogue.adapter.persistence.details;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.details.port.GameListingReadPort;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** Reads one bounded public listing through Catalogue's application boundary. */
public final class JdbcGameListingReadAdapter implements GameListingReadPort {
    private final NamedParameterJdbcOperations jdbc;

    public JdbcGameListingReadAdapter(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Listing> findListing(String gameId) {
        var parameters = Map.of("game", UUID.fromString(gameId));
        var games =
                jdbc.query(
                        """
                SELECT gs.*, coalesce(fe.first_release_date, launch.period_start) AS first_release_on,
                       attention.visits AS catalogue_visits
                FROM catalogue.game_snapshot gs
                JOIN catalogue.catalogue_publication cp USING (publication_id)
                LEFT JOIN catalogue.game_featured_evidence fe ON fe.game_id = gs.game_id
                LEFT JOIN catalogue.game_attention_evidence attention ON attention.game_id = gs.game_id
                LEFT JOIN LATERAL (
                    SELECT r.period_start FROM catalogue.release_snapshot r
                    WHERE r.publication_id = gs.publication_id AND r.game_id = gs.game_id
                      AND r.period_start IS NOT NULL AND r.release_stage IN ('full_release', 'unknown')
                      AND r.release_status NOT IN ('cancelled', 'delayed')
                    ORDER BY r.period_start, r.release_id LIMIT 1
                ) launch ON fe.first_release_date IS NULL
                WHERE cp.is_current AND gs.game_id = :game
                """,
                        parameters,
                        (rs, row) ->
                                new Listing(
                                        rs.getString("game_id"),
                                        rs.getString("slug"),
                                        rs.getString("canonical_title"),
                                        rs.getString("normalized_title"),
                                        List.of(),
                                        CatalogueCoverReferenceRowMapper.map(rs),
                                        List.of(),
                                        rs.getObject("first_release_on", LocalDate.class),
                                        rs.getObject("catalogue_visits", Double.class)));
        if (games.isEmpty()) return Optional.empty();
        var aliases =
                jdbc.query(
                        """
                SELECT ga.normalized_alias FROM catalogue.game_alias ga
                JOIN catalogue.catalogue_publication cp USING (publication_id)
                WHERE cp.is_current AND ga.game_id = :game AND ga.approval_status = 'approved'
                ORDER BY ga.normalized_alias LIMIT 101
                """,
                        parameters,
                        (rs, row) -> rs.getString(1));
        if (aliases.size() > 100)
            throw new CatalogueDataInvalidException(
                    new IllegalStateException("Game listing exceeds the supported alias bound"));
        var game = games.getFirst();
        var publication =
                com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader.read(
                                jdbc)
                        .orElseThrow();
        var genres =
                com.videogameplatform.catalogue.adapter.persistence.CatalogueGenresReader.read(
                                jdbc, publication.id(), List.of(gameId), 51)
                        .getOrDefault(gameId, List.of());
        if (genres.size() > 50)
            throw new CatalogueDataInvalidException(
                    new IllegalStateException("Game listing exceeds the supported genre bound"));
        return Optional.of(
                new Listing(
                        game.gameId(),
                        game.slug(),
                        game.title(),
                        game.normalizedTitle(),
                        aliases,
                        game.cover(),
                        genres,
                        game.firstReleaseOn(),
                        game.catalogueVisits()));
    }
}
