package com.videogameplatform.catalogue.adapter.persistence.details;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.port.GameDetailsReadPort;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;

/** Indexed per-game reads within the existing bounded repeatable-read execution policy. */
public final class JdbcGameDetailsReadAdapter implements GameDetailsReadPort {
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcGameDetailsReadAdapter(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Optional<Game> find(String gameId) {
        // Product identifiers are opaque at the API boundary; non-UUID identifiers simply do not
        // exist.
        if (gameId == null
                || !gameId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            return Optional.empty();
        }
        try {
            return transaction.execute(status -> read(gameId));
        } catch (CannotCreateTransactionException
                | DataAccessResourceFailureException
                | RecoverableDataAccessException
                | TransientDataAccessException exception) {
            throw new CatalogueReadException(exception);
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }

    private Optional<Game> read(String gameId) {
        var publication =
                CurrentPublicationReader.read(jdbc).orElseThrow(CatalogueNotReadyException::new);
        var params =
                Map.of(
                        "publication",
                        UUID.fromString(publication.id()),
                        "game",
                        UUID.fromString(gameId));
        var games =
                jdbc.query(
                        """
            SELECT * FROM catalogue.game_snapshot
            WHERE publication_id = :publication AND game_id = :game
            """,
                        params,
                        (rs, row) ->
                                new Game(
                                        rs.getString("game_id"),
                                        rs.getString("slug"),
                                        rs.getString("canonical_title"),
                                        List.of(),
                                        summary(rs),
                                        CatalogueCoverReferenceRowMapper.map(rs),
                                        List.of()));
        if (games.isEmpty()) {
            return Optional.empty();
        }
        var aliases =
                jdbc.query(
                        """
            SELECT alias FROM catalogue.game_alias
            WHERE publication_id = :publication AND game_id = :game AND approval_status = 'approved'
            ORDER BY alias LIMIT %d
            """
                                .formatted(MAX_ALIASES + 1),
                        params,
                        (rs, row) -> rs.getString("alias"));
        var releases =
                jdbc.query(
                        """
            SELECT rs.*, gs.slug, gs.canonical_title, gs.cover_reference, gs.cover_source,
                   gs.cover_usage_mode, gs.cover_alternative_text, gs.cover_source_url,
                   p.code AS platform_code, p.display_name AS platform_name,
                   r.code AS region_code, r.display_name AS region_name
            FROM catalogue.release_snapshot rs
            JOIN catalogue.game_snapshot gs USING (publication_id, game_id)
            JOIN catalogue.platform p ON p.platform_id = rs.platform_id
            JOIN catalogue.region r ON r.region_id = rs.region_id
            WHERE rs.publication_id = :publication AND rs.game_id = :game
            ORDER BY rs.period_start NULLS LAST, rs.release_id LIMIT %d
            """
                                .formatted(MAX_RELEASES + 1),
                        params,
                        JdbcGameDetailsReadAdapter::release);
        if (aliases.size() > MAX_ALIASES || releases.size() > MAX_RELEASES) {
            throw new CatalogueDataInvalidException(
                    new IllegalStateException("Game detail exceeds the supported context bound"));
        }
        var game = games.getFirst();
        return Optional.of(
                new Game(
                        game.gameId(),
                        game.slug(),
                        game.canonicalTitle(),
                        aliases,
                        game.summary(),
                        game.cover(),
                        releases));
    }

    private static GameDetailsResult.Summary summary(ResultSet rs) throws SQLException {
        String source = rs.getString("summary_source_kind");
        return new GameDetailsResult.Summary(
                rs.getString("summary_kind"),
                rs.getString("summary_text"),
                rs.getString("summary_language"),
                source == null
                        ? null
                        : new BrowseReleasesResult.Provenance(
                                BrowseReleasesResult.Source.valueOf(
                                        source.toUpperCase(Locale.ROOT)),
                                rs.getString("summary_source_name"),
                                rs.getString("summary_source_entity_type")));
    }

    private static ReleaseBrowseReadPort.Item release(ResultSet rs, int row) throws SQLException {
        return new ReleaseBrowseReadPort.Item(
                rs.getString("release_id"),
                rs.getString("game_id"),
                rs.getString("slug"),
                rs.getString("canonical_title"),
                CatalogueCoverReferenceRowMapper.map(rs),
                new ReleaseBrowseReadPort.Taxonomy(
                        rs.getString("platform_code"), rs.getString("platform_name")),
                new ReleaseBrowseReadPort.Taxonomy(
                        rs.getString("region_code"), rs.getString("region_name")),
                ReleaseDateRowMapper.map(rs),
                ReleaseStatus.valueOf(rs.getString("release_status").toUpperCase(Locale.ROOT)),
                SourceKind.valueOf(rs.getString("source_kind").toUpperCase(Locale.ROOT)),
                rs.getString("source_name"),
                rs.getString("source_entity_type"),
                instant(rs, "provider_updated_at"),
                instant(rs, "last_synchronized_at"),
                instant(rs, "last_verified_at"),
                VerificationLevel.valueOf(
                        rs.getString("verification_level").toUpperCase(Locale.ROOT)),
                ReviewStatus.valueOf(rs.getString("review_status").toUpperCase(Locale.ROOT)));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
