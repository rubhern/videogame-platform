package com.videogameplatform.catalogue.adapter.persistence;

import java.util.Map;
import java.util.Optional;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/** Reads the single current catalogue publication shared by every catalogue read. */
public final class CurrentPublicationReader {

    private static final String CURRENT_PUBLICATION_SQL =
            """
            SELECT publication_id::text, catalogue_version
            FROM catalogue.catalogue_publication
            WHERE is_current
            """;

    private CurrentPublicationReader() {}

    public record Publication(String id, String version) {}

    /**
     * Returns the current publication, or nothing when the catalogue has never been published.
     * More than one current publication is a broken invariant rather than a readable state.
     */
    public static Optional<Publication> read(NamedParameterJdbcOperations jdbcOperations) {
        return jdbcOperations.query(
                CURRENT_PUBLICATION_SQL,
                Map.of(),
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    Publication publication =
                            new Publication(
                                    resultSet.getString("publication_id"),
                                    resultSet.getString("catalogue_version"));
                    if (resultSet.next()) {
                        throw new IncorrectResultSizeDataAccessException(1, 2);
                    }
                    return Optional.of(publication);
                });
    }
}
