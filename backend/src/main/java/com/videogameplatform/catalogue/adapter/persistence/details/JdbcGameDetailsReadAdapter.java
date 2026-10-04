package com.videogameplatform.catalogue.adapter.persistence.details;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.port.GameDetailsReadPort;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseStage;
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
            SELECT g.*, c.translated_text, l.fingerprint=catalogue.spanish_source_fingerprint(g.summary_text) AS translation_current
            FROM catalogue.game_snapshot g
            LEFT JOIN catalogue.game_summary_translation l ON l.game_id=g.game_id
            LEFT JOIN catalogue.content_translation c ON c.fingerprint=l.fingerprint
              AND g.summary_kind='sourced' AND g.summary_language='en'
              AND g.summary_source_kind='external_provider' AND g.summary_source_name='IGDB'
            WHERE g.publication_id = :publication AND g.game_id = :game
            """,
                        params,
                        (rs, row) ->
                                new Game(
                                        rs.getString("game_id"),
                                        rs.getString("slug"),
                                        rs.getString("canonical_title"),
                                        List.of(),
                                        summary(rs),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
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
        // Platform by platform, each platform's releases in presentation precedence with the
        // earliest date first, so the first release of a platform, and of a platform and region,
        // is the presented one. Every release is still returned for eligibility and evidence.
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
            ORDER BY min(rs.period_start) OVER (PARTITION BY rs.platform_id) NULLS LAST,
                     lower(p.display_name), rs.platform_id, %s
            LIMIT %d
            """
                                .formatted(
                                        ReleasePresentationOrder.of(
                                                "rs", "r", "rs.period_start ASC NULLS LAST"),
                                        MAX_RELEASES + 1),
                        params,
                        JdbcGameDetailsReadAdapter::release);
        var credits =
                jdbc.query(
                        """
            SELECT gc.company_role, c.company_id, c.display_name
            FROM catalogue.game_company gc
            JOIN catalogue.company c ON c.company_id = gc.company_id
            WHERE gc.game_id = :game
            ORDER BY gc.company_role, lower(c.display_name), c.company_id
            LIMIT %d
            """
                                .formatted(MAX_COMPANY_CREDITS + 1),
                        params,
                        (rs, row) ->
                                Map.entry(
                                        rs.getString("company_role"),
                                        new GameDetailsResult.Company(
                                                rs.getString("company_id"),
                                                rs.getString("display_name"))));
        var genres =
                terms("catalogue.game_genre", "catalogue.genre", "genre_id", params, MAX_GENRES);
        var gameModes =
                terms(
                        "catalogue.game_game_mode",
                        "catalogue.game_mode",
                        "game_mode_id",
                        params,
                        MAX_GAME_MODES);
        if (aliases.size() > MAX_ALIASES
                || releases.size() > MAX_RELEASES
                || credits.size() > MAX_COMPANY_CREDITS
                || genres.size() > MAX_GENRES
                || gameModes.size() > MAX_GAME_MODES) {
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
                        credited(credits, "developer"),
                        credited(credits, "publisher"),
                        genres,
                        gameModes,
                        game.cover(),
                        releases));
    }

    private static List<GameDetailsResult.Company> credited(
            List<Map.Entry<String, GameDetailsResult.Company>> credits, String role) {
        return credits.stream()
                .filter(credit -> credit.getKey().equals(role))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** A game's genres or game modes; {@code links}, {@code terms} and {@code id} are constants. */
    private List<GameDetailsResult.Term> terms(
            String links, String terms, String id, Map<String, ?> params, int bound) {
        return jdbc.query(
                """
            SELECT t.code, t.display_name
            FROM %s l
            JOIN %s t ON t.%s = l.%s
            WHERE l.game_id = :game
            ORDER BY lower(t.display_name), t.code
            LIMIT %d
            """
                        .formatted(links, terms, id, id, bound + 1),
                params,
                (rs, row) ->
                        new GameDetailsResult.Term(
                                rs.getString("code"), rs.getString("display_name")));
    }

    private static GameDetailsResult.Summary summary(ResultSet rs) throws SQLException {
        String source = rs.getString("summary_source_kind");
        return new GameDetailsResult.Summary(
                rs.getString("summary_kind"),
                rs.getString("translated_text") == null
                        ? rs.getString("summary_text")
                        : rs.getString("translated_text"),
                rs.getString("translated_text") == null ? rs.getString("summary_language") : "es",
                source == null
                        ? null
                        : new BrowseReleasesResult.Provenance(
                                BrowseReleasesResult.Source.valueOf(
                                        source.toUpperCase(Locale.ROOT)),
                                rs.getString("summary_source_name"),
                                rs.getString("summary_source_entity_type")),
                rs.getString("translated_text") == null
                        ? null
                        : new GameDetailsResult.Translation(
                                rs.getString("summary_text"),
                                rs.getString("summary_language"),
                                rs.getBoolean("translation_current")));
    }

    private static ReleaseBrowseReadPort.ReleaseRow release(ResultSet rs, int row)
            throws SQLException {
        return new ReleaseBrowseReadPort.ReleaseRow(
                rs.getString("release_id"),
                rs.getString("game_id"),
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
                ReviewStatus.valueOf(rs.getString("review_status").toUpperCase(Locale.ROOT)),
                ReleaseStage.fromValue(rs.getString("release_stage")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
