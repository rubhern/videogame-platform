package com.videogameplatform.catalogue.adapter.persistence.details;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.details.port.GameListingReadPort;
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
                SELECT gs.* FROM catalogue.game_snapshot gs
                JOIN catalogue.catalogue_publication cp USING (publication_id)
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
                                        CatalogueCoverReferenceRowMapper.map(rs)));
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
        return Optional.of(
                new Listing(
                        game.gameId(),
                        game.slug(),
                        game.title(),
                        game.normalizedTitle(),
                        aliases,
                        game.cover()));
    }
}
