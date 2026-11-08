package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueGenresReader;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult.Taxonomy;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.application.releases.port.UpcomingHighlightsReadPort;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL read adapter for a platform's upcoming highlights (UC-020).
 *
 * <p>PostgreSQL does all the work in one statement: qualifying_release holds the scope's qualifying
 * releases inside the horizon through the shared period index, candidate keeps the distinct games
 * without a scope release that has already occurred and not known to be editions, ranked_game ranks
 * them by Hypes through a primary-key probe and keeps the bounded top, and presented_release keeps
 * one release per ranked game. Work grows with the releases of the horizon, never with the
 * catalogue's history, and request memory stays O(limit).
 */
public final class JdbcUpcomingHighlightsReadAdapter implements UpcomingHighlightsReadPort {

    private static final int GENRE_LIMIT = 2;

    static final String PLATFORMS =
            """
            SELECT p.platform_id::text AS platform_id, p.display_name
            FROM catalogue.platform p
            WHERE p.platform_id::text IN (:platformIds)
            ORDER BY lower(p.display_name), p.platform_id
            """;

    /**
     * A qualifying release is a Full Release on a scope platform, neither cancelled, delayed nor
     * pending review, whose known date can only fall after the evaluation date and inside the
     * horizon: an exact day after today, or a month or quarter that has not ended, ending by the
     * horizon's last day. A year or unknown date is never placed inside the horizon. The overlap
     * condition is implied by the others and lets the shared period index find the horizon.
     *
     * <p>A candidate has no scope release that has already effectively occurred (the occurrence
     * rule of Explorar catálogo, REL-012: delayed evidence never occurs, an unknown date only with
     * explicit released evidence), so a game already out on the platform is never "upcoming" there,
     * while one released elsewhere still is. A game whose stored evidence says it is an edition
     * rather than a distinct product is never a candidate; a game without evidence stays one, so it
     * reads as unranked rather than absent.
     */
    static final String READ =
            """
            WITH qualifying_release AS MATERIALIZED (
                SELECT rs.*
                FROM catalogue.release_snapshot rs
                WHERE rs.publication_id = CAST(:publicationId AS uuid)
                  AND rs.platform_id = ANY(CAST(:platformIds AS uuid[]))
                  AND rs.release_status NOT IN ('cancelled', 'delayed')
                  AND rs.review_status <> 'required'
                  AND rs.release_stage = 'full_release'
                  AND rs.date_precision IN ('day', 'month', 'quarter')
                  AND rs.period_start IS NOT NULL
                  AND daterange(rs.period_start, rs.period_end, '[]')
                      && daterange(CAST(:evaluatedOn AS date), CAST(:horizonEnd AS date), '[]')
                  AND rs.period_end <= CAST(:horizonEnd AS date)
                  AND (rs.period_end > CAST(:evaluatedOn AS date)
                       OR (rs.date_precision <> 'day'
                           AND rs.period_end = CAST(:evaluatedOn AS date)))
            ),
            candidate AS MATERIALIZED (
                SELECT DISTINCT qr.game_id
                FROM qualifying_release qr
                WHERE NOT EXISTS (
                        SELECT 1
                        FROM catalogue.release_snapshot occurred
                        WHERE occurred.publication_id = qr.publication_id
                          AND occurred.game_id = qr.game_id
                          AND occurred.platform_id = ANY(CAST(:platformIds AS uuid[]))
                          AND occurred.release_status NOT IN ('cancelled', 'delayed')
                          AND ((occurred.date_precision = 'day'
                                  AND occurred.exact_date <= CAST(:evaluatedOn AS date))
                               OR (occurred.date_precision IN ('month', 'quarter', 'year')
                                  AND occurred.period_end < CAST(:evaluatedOn AS date))
                               OR (occurred.date_precision = 'unknown'
                                  AND occurred.release_status = 'released')))
                  AND NOT EXISTS (
                        SELECT 1
                        FROM catalogue.game_featured_evidence edition
                        WHERE edition.game_id = qr.game_id AND NOT edition.eligible_product)
            ),
            ranked_game AS MATERIALIZED (
                SELECT c.game_id, signal.hypes, signal.observed_at
                FROM candidate c
                JOIN catalogue.game_featured_evidence signal
                  ON signal.game_id = c.game_id AND signal.eligible_product AND signal.hypes > 0
                ORDER BY signal.hypes DESC, c.game_id
                LIMIT :limit
            ),
            presented_release AS MATERIALIZED (
                SELECT DISTINCT ON (qr.game_id) qr.*
                FROM qualifying_release qr
                JOIN ranked_game rg ON rg.game_id = qr.game_id
                JOIN catalogue.region release_region ON release_region.region_id = qr.region_id
                ORDER BY qr.game_id, %s
            ),
            summary AS (
                SELECT EXISTS (SELECT 1 FROM candidate) AS has_candidates
            ),
            page AS (
                SELECT rg.game_id, rg.hypes, rg.observed_at,
                       snapshot.slug, snapshot.canonical_title,
                       snapshot.cover_reference, snapshot.cover_source, snapshot.cover_usage_mode,
                       snapshot.cover_alternative_text, snapshot.cover_source_url,
                       image.media_kind AS image_kind, image.image_reference AS image_reference,
                       image.width AS image_width, image.height AS image_height,
                       image.transparent AS image_transparent, image.source_name AS image_source,
                       image.source_url AS image_source_url,
                       card_image.media_kind AS card_image_kind,
                       card_image.image_reference AS card_image_reference,
                       card_image.width AS card_image_width, card_image.height AS card_image_height,
                       card_image.transparent AS card_image_transparent,
                       card_image.source_name AS card_image_source,
                       card_image.source_url AS card_image_source_url,
                       pr.platform_id, platform.display_name AS platform_name,
                       pr.region_id, region.display_name AS region_name,
                       pr.date_precision, pr.exact_date, pr.release_year, pr.release_month,
                       pr.release_quarter, pr.release_status
                FROM ranked_game rg
                JOIN catalogue.game_snapshot snapshot
                  ON snapshot.publication_id = CAST(:publicationId AS uuid)
                 AND snapshot.game_id = rg.game_id
                JOIN presented_release pr ON pr.game_id = rg.game_id
                JOIN catalogue.platform platform ON platform.platform_id = pr.platform_id
                JOIN catalogue.region region ON region.region_id = pr.region_id
                LEFT JOIN catalogue.game_featured_media image
                  ON image.game_id = rg.game_id AND image.media_role = 'image'
                LEFT JOIN catalogue.game_featured_media card_image
                  ON card_image.game_id = rg.game_id AND card_image.media_role = 'card_image'
            )
            SELECT summary.has_candidates, page.*, page.game_id::text AS ranked_game_id,
                   page.platform_id::text AS presented_platform_id,
                   page.region_id::text AS presented_region_id
            FROM summary
            LEFT JOIN page ON true
            ORDER BY page.hypes DESC, page.game_id
            """
                    .formatted(
                            ReleasePresentationOrder.of(
                                    "qr", "release_region", "qr.period_end ASC"));

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcUpcomingHighlightsReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> findUpcomingHighlights(Criteria criteria) {
        return CatalogueReadTransaction.execute(
                readTransaction, status -> findInTransaction(criteria));
    }

    private Optional<Result> findInTransaction(Criteria criteria) {
        Optional<CurrentPublicationReader.Publication> current =
                CurrentPublicationReader.read(jdbcOperations);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String publicationId = current.orElseThrow().id();

        // Only identifiers the taxonomy holds reach a uuid cast; an unknown one stops the read and
        // the application rejects the request by finding it missing.
        List<Taxonomy> platforms =
                jdbcOperations.query(
                        PLATFORMS,
                        Map.of("platformIds", criteria.platformIds()),
                        (resultSet, row) ->
                                new Taxonomy(
                                        resultSet.getString("platform_id"),
                                        resultSet.getString("display_name")));
        if (platforms.size() != criteria.platformIds().size()) {
            return Optional.of(new Result(platforms, false, List.of()));
        }

        Page page =
                jdbcOperations.query(
                        READ,
                        parameters(publicationId, criteria, platforms),
                        (ResultSetExtractor<Page>) JdbcUpcomingHighlightsReadAdapter::page);
        var genres =
                CatalogueGenresReader.read(
                        jdbcOperations,
                        publicationId,
                        page.items().stream().map(Item::gameId).toList(),
                        GENRE_LIMIT);
        List<Item> items =
                page.items().stream()
                        .map(
                                item ->
                                        new Item(
                                                item.gameId(),
                                                item.slug(),
                                                item.canonicalTitle(),
                                                item.cover(),
                                                item.popularityObservedAt(),
                                                item.image(),
                                                item.cardImage(),
                                                genres.getOrDefault(item.gameId(), List.of()),
                                                item.platform(),
                                                item.region(),
                                                item.releaseDate(),
                                                item.status()))
                        .toList();
        return Optional.of(new Result(platforms, page.candidates(), items));
    }

    static Map<String, Object> parameters(
            String publicationId, Criteria criteria, List<Taxonomy> platforms) {
        return Map.of(
                "publicationId",
                publicationId,
                // A PostgreSQL array literal of identifiers the taxonomy holds, so they are uuids.
                "platformIds",
                platforms.stream().map(Taxonomy::id).collect(Collectors.joining(",", "{", "}")),
                "evaluatedOn",
                criteria.evaluatedOn(),
                "horizonEnd",
                criteria.horizonEnd(),
                "limit",
                criteria.limit());
    }

    /** One row per ranked game; with no ranked game, one row carries the summary alone. */
    private static Page page(ResultSet resultSet) throws SQLException {
        if (!resultSet.next()) {
            throw new CatalogueDataInvalidException(
                    new IllegalStateException("The upcoming highlights read returned no summary"));
        }
        boolean candidates = resultSet.getBoolean("has_candidates");
        List<Item> items = new ArrayList<>();
        try {
            do {
                if (resultSet.getString("ranked_game_id") != null) {
                    items.add(item(resultSet));
                }
            } while (resultSet.next());
        } catch (IllegalArgumentException
                | IllegalStateException
                | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
        return new Page(candidates, items);
    }

    private static Item item(ResultSet resultSet) throws SQLException {
        return new Item(
                resultSet.getString("ranked_game_id"),
                resultSet.getString("slug"),
                resultSet.getString("canonical_title"),
                CatalogueCoverReferenceRowMapper.map(resultSet),
                ReleaseGroupPageMapper.instant(resultSet, "observed_at"),
                media(resultSet, "image"),
                media(resultSet, "card_image"),
                List.of(),
                new Taxonomy(
                        resultSet.getString("presented_platform_id"),
                        resultSet.getString("platform_name")),
                new Taxonomy(
                        resultSet.getString("presented_region_id"),
                        resultSet.getString("region_name")),
                ReleaseDateRowMapper.map(resultSet),
                ReleaseStatus.fromValue(resultSet.getString("release_status")));
    }

    /** A stored selection of one role, or empty when the Game has none. */
    private static Optional<MediaReference> media(ResultSet resultSet, String role)
            throws SQLException {
        String reference = resultSet.getString(role + "_reference");
        if (reference == null) {
            return Optional.empty();
        }
        return Optional.of(
                new MediaReference(
                        FeaturedMediaPolicy.ImageKind.valueOf(
                                resultSet.getString(role + "_kind").toUpperCase(Locale.ROOT)),
                        resultSet.getString(role + "_source"),
                        reference,
                        resultSet.getInt(role + "_width"),
                        resultSet.getInt(role + "_height"),
                        resultSet.getBoolean(role + "_transparent"),
                        resultSet.getString(role + "_source_url")));
    }

    private record Page(boolean candidates, List<Item> items) {}
}
