package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueGenresReader;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueSummaryRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL read adapter for one calendar month's featured releases (UC-010).
 *
 * <p>PostgreSQL does all the work: qualifying_release holds the month's qualifying releases through
 * the shared period index, ranked_game ranks their distinct games by the local popularity signal and
 * keeps only the bounded top, and presented_release keeps one presented release per game and
 * platform among those games. Only the ranked games read their snapshot and their stored featured
 * hero/card images and card logo, each by key. Work grows with the month's releases, never with the catalogue, the
 * popularity table or the media table, and request memory stays O(limit x releaseGroupLimit).
 */
public final class JdbcFeaturedReleaseReadAdapter implements FeaturedReleaseReadPort {

    // A release qualifies when its known date lies inside the month: an exact day of the month, or
    // month precision equal to the month (quarter and year periods are wider than any month, and an
    // unknown date has no period). Negative lifecycle evidence and pending review never qualify;
    // only a Full Release qualifies.
    private static final String QUALIFYING_PREDICATE =
            """
            rs.publication_id = CAST(:publicationId AS uuid)
              AND rs.release_status NOT IN ('cancelled', 'delayed')
              AND rs.review_status <> 'required'
              AND rs.release_stage = 'full_release'
              AND rs.date_precision IN ('day', 'month')
              AND rs.period_start IS NOT NULL
              AND daterange(rs.period_start, rs.period_end, '[]')
                  <@ daterange(CAST(:monthStart AS date), CAST(:monthEnd AS date), '[]')
            """;

    private static final String QUALIFYING_EXISTS_SQL =
            "SELECT EXISTS (SELECT 1 FROM catalogue.release_snapshot rs WHERE "
                    + QUALIFYING_PREDICATE
                    + " AND EXISTS (SELECT 1 FROM catalogue.game_featured_evidence evidence"
                    + " WHERE evidence.game_id = rs.game_id AND evidence.eligible_product"
                    + " AND evidence.first_release_date BETWEEN CAST(:monthStart AS date) AND CAST(:monthEnd AS date)))";

    // Each candidate reads its signal through a LATERAL primary-key probe (the LIMIT keeps the
    // planner from hashing the whole popularity table), and only the ranked games join their
    // snapshot: the release foreign key guarantees one in the same publication, so joining after
    // the bound changes nothing. The presented release of each platform follows the shared
    // precedence with the earliest date first; inside a game an exact day precedes month precision,
    // then the earlier date. The page keeps the ranking order: highest popularity first, then the
    // unique game id.
    private static final String PAGE_SQL =
            """
            WITH qualifying_release AS MATERIALIZED (
                SELECT rs.* FROM catalogue.release_snapshot rs WHERE %1$s
            ),
            ranked_game AS MATERIALIZED (
                SELECT candidate.game_id, popularity.hypes, popularity.observed_at
                FROM (SELECT DISTINCT qr.game_id FROM qualifying_release qr) candidate
                JOIN LATERAL (
                    SELECT signal.hypes, signal.observed_at
                    FROM catalogue.game_featured_evidence signal
                    WHERE signal.game_id = candidate.game_id
                      AND signal.eligible_product
                      AND signal.hypes > 0
                      AND signal.first_release_date BETWEEN CAST(:monthStart AS date) AND CAST(:monthEnd AS date)
                    LIMIT 1
                ) popularity ON true
                ORDER BY popularity.hypes DESC, candidate.game_id
                LIMIT :limit
            ),
            presented_release AS MATERIALIZED (
                SELECT DISTINCT ON (qr.game_id, qr.platform_id) qr.*
                FROM qualifying_release qr
                JOIN ranked_game rg ON rg.game_id = qr.game_id
                JOIN catalogue.region release_region ON release_region.region_id = qr.region_id
                ORDER BY qr.game_id, qr.platform_id, %2$s
            )
            SELECT rg.game_id::text AS game_id, snapshot.slug, snapshot.canonical_title,
                   snapshot.cover_reference, snapshot.cover_source, snapshot.cover_usage_mode,
                   snapshot.cover_alternative_text, snapshot.cover_source_url,
                   rg.observed_at AS popularity_observed_at,
                   image.media_kind AS image_kind, image.image_reference AS image_reference,
                   image.width AS image_width, image.height AS image_height,
                   image.transparent AS image_transparent, image.source_name AS image_source,
                   image.source_url AS image_source_url,
                   card_image.media_kind AS card_image_kind, card_image.image_reference AS card_image_reference,
                   card_image.width AS card_image_width, card_image.height AS card_image_height,
                   card_image.transparent AS card_image_transparent, card_image.source_name AS card_image_source,
                   card_image.source_url AS card_image_source_url,
                   logo.media_kind AS logo_kind, logo.image_reference AS logo_reference,
                   logo.width AS logo_width, logo.height AS logo_height,
                   logo.transparent AS logo_transparent, logo.source_name AS logo_source,
                   logo.source_url AS logo_source_url,
                   rel.release_id::text AS release_id,
                   rel.platform_id::text AS platform_id, rel.platform_name,
                   rel.region_id::text AS region_id, rel.region_name,
                   rel.date_precision, rel.exact_date, rel.release_year, rel.release_month,
                   rel.release_quarter, rel.release_stage, rel.release_status, rel.source_kind,
                   rel.source_name, rel.source_entity_type, rel.provider_updated_at,
                   rel.last_synchronized_at, rel.last_verified_at, rel.verification_level,
                   rel.review_status
            FROM ranked_game rg
            JOIN catalogue.game_snapshot snapshot
              ON snapshot.publication_id = CAST(:publicationId AS uuid)
             AND snapshot.game_id = rg.game_id
            LEFT JOIN catalogue.game_featured_media image
              ON image.game_id = rg.game_id AND image.media_role = 'image'
            LEFT JOIN catalogue.game_featured_media card_image
              ON card_image.game_id = rg.game_id AND card_image.media_role = 'card_image'
            LEFT JOIN catalogue.game_featured_media logo
              ON logo.game_id = rg.game_id AND logo.media_role = 'logo'
            JOIN LATERAL (
                SELECT p.*, pl.display_name AS platform_name, r.display_name AS region_name,
                       %3$s AS precision_rank
                FROM presented_release p
                JOIN catalogue.platform pl ON pl.platform_id = p.platform_id
                JOIN catalogue.region r ON r.region_id = p.region_id
                WHERE p.game_id = rg.game_id
                ORDER BY %3$s, p.period_start, p.release_id
                LIMIT :releaseGroupLimit
            ) rel ON true
            ORDER BY rg.hypes DESC, rg.game_id, rel.precision_rank, rel.period_start,
                     rel.release_id
            """
                    .formatted(
                            QUALIFYING_PREDICATE,
                            ReleasePresentationOrder.of(
                                    "qr", "release_region", "qr.period_start ASC"),
                            ReleasePresentationOrder.precisionRank("p"));

    private static final String SUMMARY_SQL =
            """
                SELECT g.summary_kind, g.summary_text, g.summary_language,
                       g.summary_source_kind, g.summary_source_name, g.summary_source_entity_type,
                       c.translated_text,
                       l.fingerprint=catalogue.spanish_source_fingerprint(g.summary_text) AS translation_current
                FROM catalogue.game_snapshot g
                LEFT JOIN catalogue.game_summary_translation l ON l.game_id=g.game_id
                LEFT JOIN catalogue.content_translation c ON c.fingerprint=l.fingerprint
                  AND g.summary_kind='sourced' AND g.summary_language='en'
                  AND g.summary_source_kind='external_provider' AND g.summary_source_name='IGDB'
                WHERE g.publication_id = CAST(:publicationId AS uuid) AND g.game_id = CAST(:leadId AS uuid)
                """;

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcFeaturedReleaseReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> findFeaturedReleases(Criteria criteria) {
        try {
            return readTransaction.execute(status -> findInTransaction(criteria));
        } catch (CannotCreateTransactionException
                | DataAccessResourceFailureException
                | RecoverableDataAccessException
                | TransientDataAccessException exception) {
            throw new CatalogueReadException(exception);
        } catch (DataAccessException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }

    private Optional<Result> findInTransaction(Criteria criteria) {
        Optional<CurrentPublicationReader.Publication> publication =
                CurrentPublicationReader.read(jdbcOperations);
        if (publication.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("publicationId", publication.orElseThrow().id());
        parameters.put("monthStart", criteria.monthStart());
        parameters.put("monthEnd", criteria.monthEnd());
        parameters.put("limit", criteria.limit());
        parameters.put("releaseGroupLimit", criteria.releaseGroupLimit());

        List<Item> items =
                enrich(jdbcOperations.query(PAGE_SQL, parameters, this::mapPage), parameters);
        // A ranked game proves the month qualifies; only an empty ranking needs the cheap probe
        // that
        // tells an unranked month apart from a month without qualifying releases.
        boolean qualifying =
                !items.isEmpty()
                        || Boolean.TRUE.equals(
                                jdbcOperations.queryForObject(
                                        QUALIFYING_EXISTS_SQL, parameters, Boolean.class));
        return Optional.of(new Result(publication.orElseThrow().version(), qualifying, items));
    }

    /** Two indexed reads enrich only the ranked page: at most twelve terms and one summary. */
    private List<Item> enrich(List<Item> items, Map<String, Object> parameters) {
        if (items.isEmpty()) return items;
        var genres =
                CatalogueGenresReader.read(
                        jdbcOperations,
                        parameters.get("publicationId").toString(),
                        items.stream().map(Item::gameId).toList(),
                        2);
        parameters.put("leadId", items.getFirst().gameId());
        GameDetailsResult.Summary summary =
                jdbcOperations.queryForObject(
                        SUMMARY_SQL, parameters, (rs, row) -> CatalogueSummaryRowMapper.map(rs));
        return items.stream()
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
                                        item.logo(),
                                        item.releases(),
                                        genres.getOrDefault(item.gameId(), List.of()),
                                        item == items.getFirst()
                                                ? Optional.ofNullable(summary)
                                                : Optional.empty()))
                .toList();
    }

    /** Folds the bounded join rows back into one ranked item per game, keeping SQL order. */
    private List<Item> mapPage(ResultSet resultSet) throws SQLException {
        Map<String, MutableItem> items = new LinkedHashMap<>();
        try {
            while (resultSet.next()) {
                String gameId = resultSet.getString("game_id");
                MutableItem item = items.get(gameId);
                if (item == null) {
                    item =
                            new MutableItem(
                                    gameId,
                                    resultSet.getString("slug"),
                                    resultSet.getString("canonical_title"),
                                    CatalogueCoverReferenceRowMapper.map(resultSet),
                                    ReleaseGroupPageMapper.instant(
                                            resultSet, "popularity_observed_at"),
                                    media(resultSet, "image"),
                                    media(resultSet, "card_image"),
                                    media(resultSet, "logo"));
                    items.put(gameId, item);
                }
                item.releases.add(ReleaseGroupPageMapper.releaseRow(resultSet, gameId));
            }
        } catch (IllegalArgumentException
                | IllegalStateException
                | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
        return items.values().stream().map(MutableItem::toItem).toList();
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

    private static final class MutableItem {

        private final String gameId;
        private final String slug;
        private final String canonicalTitle;
        private final CatalogueCoverReference cover;
        private final Instant popularityObservedAt;
        private final Optional<MediaReference> image;
        private final Optional<MediaReference> cardImage;
        private final Optional<MediaReference> logo;
        private final List<ReleaseRow> releases = new ArrayList<>();

        private MutableItem(
                String gameId,
                String slug,
                String canonicalTitle,
                CatalogueCoverReference cover,
                Instant popularityObservedAt,
                Optional<MediaReference> image,
                Optional<MediaReference> cardImage,
                Optional<MediaReference> logo) {
            this.gameId = gameId;
            this.slug = slug;
            this.canonicalTitle = canonicalTitle;
            this.cover = cover;
            this.popularityObservedAt = popularityObservedAt;
            this.image = image;
            this.cardImage = cardImage;
            this.logo = logo;
        }

        private Item toItem() {
            return new Item(
                    gameId,
                    slug,
                    canonicalTitle,
                    cover,
                    popularityObservedAt,
                    image,
                    cardImage,
                    logo,
                    releases,
                    List.of(),
                    Optional.empty());
        }
    }
}
