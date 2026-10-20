package com.videogameplatform.catalogue.adapter.persistence.preferences;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.application.preferences.ListPlatformsUseCase.PlatformList;
import com.videogameplatform.catalogue.application.preferences.PlatformOption;
import com.videogameplatform.catalogue.application.preferences.port.SelectablePlatformsReadPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL read adapter for the selectable platforms: the taxonomy entries a current release
 * uses. Each platform is probed once through the publication/platform release index, so work grows
 * with the bounded taxonomy, never with the release table.
 */
public final class JdbcSelectablePlatformsReadAdapter implements SelectablePlatformsReadPort {

    private static final String PLATFORMS_SQL =
            """
            SELECT p.platform_id::text AS platform_id, p.display_name
            FROM catalogue.platform p
            WHERE EXISTS (
                SELECT 1 FROM catalogue.release_snapshot rs
                WHERE rs.publication_id = CAST(:publicationId AS uuid)
                  AND rs.platform_id = p.platform_id
            )
            ORDER BY lower(p.display_name), p.platform_id
            LIMIT :limit
            """;

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcSelectablePlatformsReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<PlatformList> find(int limit) {
        return CatalogueReadTransaction.execute(
                readTransaction,
                status ->
                        CurrentPublicationReader.read(jdbcOperations)
                                .map(publication -> read(publication, limit)));
    }

    private PlatformList read(CurrentPublicationReader.Publication publication, int limit) {
        List<PlatformOption> platforms =
                jdbcOperations.query(
                        PLATFORMS_SQL,
                        Map.of("publicationId", publication.id(), "limit", limit),
                        (resultSet, row) ->
                                new PlatformOption(
                                        resultSet.getString("platform_id"),
                                        resultSet.getString("display_name")));
        return new PlatformList(publication.version(), platforms);
    }
}
