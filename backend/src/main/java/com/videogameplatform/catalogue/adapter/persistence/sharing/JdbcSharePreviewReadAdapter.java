package com.videogameplatform.catalogue.adapter.persistence.sharing;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.synchronization.JdbcCatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.sharing.port.SharePreviewReadPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * One statement per preview, each on a unique key: {@code uq_current_game (game_id)} and the
 * translation and platform primary keys. Nothing here grows with the catalogue.
 */
public final class JdbcSharePreviewReadAdapter implements SharePreviewReadPort {
    // The summary follows the game page: its local Spanish translation when one exists. The stored
    // "no summary yet" notice is the representation of an absent summary, so it reads as none.
    private static final String GAME_SQL =
            """
            SELECT g.game_id::text AS game_id, g.slug, g.canonical_title,
                   CASE WHEN g.summary_kind = 'editorial' AND g.summary_text = :noSummary
                        THEN NULL
                        ELSE coalesce(c.translated_text, g.summary_text)
                   END AS summary,
                   g.cover_reference, g.cover_source, g.cover_usage_mode,
                   g.cover_alternative_text, g.cover_source_url
            FROM catalogue.game_snapshot g
            JOIN catalogue.catalogue_publication cp
              ON cp.publication_id = g.publication_id AND cp.is_current
            LEFT JOIN catalogue.game_summary_translation l ON l.game_id = g.game_id
            LEFT JOIN catalogue.content_translation c ON c.fingerprint = l.fingerprint
              AND g.summary_kind = 'sourced' AND g.summary_language = 'en'
              AND g.summary_source_kind = 'external_provider' AND g.summary_source_name = 'IGDB'
            WHERE g.game_id = :game
            """;

    private static final String PLATFORM_SQL =
            "SELECT display_name FROM catalogue.platform WHERE platform_id = :platform";

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcSharePreviewReadAdapter(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Optional<Game> findGame(UUID gameId) {
        try {
            return CatalogueReadTransaction.execute(
                    transaction,
                    status ->
                            jdbc
                                    .query(
                                            GAME_SQL,
                                            Map.of(
                                                    "game",
                                                    gameId,
                                                    "noSummary",
                                                    JdbcCatalogueSynchronizationStore
                                                            .NO_SUMMARY_TEXT),
                                            (rs, row) ->
                                                    new Game(
                                                            rs.getString("game_id"),
                                                            rs.getString("slug"),
                                                            rs.getString("canonical_title"),
                                                            rs.getString("summary"),
                                                            CatalogueCoverReferenceRowMapper.map(
                                                                    rs)))
                                    .stream()
                                    .findFirst());
        } catch (IllegalArgumentException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }

    @Override
    public Optional<String> findPlatformName(UUID platformId) {
        return CatalogueReadTransaction.execute(
                transaction,
                status ->
                        jdbc
                                .queryForList(
                                        PLATFORM_SQL, Map.of("platform", platformId), String.class)
                                .stream()
                                .findFirst());
    }
}
