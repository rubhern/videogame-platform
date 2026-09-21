package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL read adapter that returns only the requested release page. */
public final class JdbcReleaseBrowseReadAdapter implements ReleaseBrowseReadPort {

    private static final String PLATFORM_SQL =
            """
            SELECT platform_id::text, display_name
            FROM catalogue.platform
            """;
    private static final String REGION_SQL =
            """
            SELECT region_id::text, display_name
            FROM catalogue.region
            """;
    private static final String COUNT_SELECT = "SELECT count(*) FROM catalogue.release_snapshot rs";
    private static final String PAGE_PREFIX =
            "WITH filtered_release AS MATERIALIZED ("
                    + "SELECT * FROM catalogue.release_snapshot rs";
    private static final String PAGE_SELECT =
            """
            SELECT rs.release_id::text,
                   rs.game_id::text,
                   gs.slug,
                   gs.canonical_title,
                   gs.cover_reference,
                   gs.cover_source,
                   gs.cover_usage_mode,
                   gs.cover_alternative_text,
                   gs.cover_source_url,
                   p.platform_id::text AS platform_id,
                   p.display_name AS platform_name,
                   r.region_id::text AS region_id,
                   r.display_name AS region_name,
                   rs.date_precision,
                   rs.exact_date,
                   rs.release_year,
                   rs.release_month,
                   rs.release_quarter,
                   rs.release_status,
                   rs.source_kind,
                   rs.source_name,
                   rs.source_entity_type,
                   rs.provider_updated_at,
                   rs.last_synchronized_at,
                   rs.last_verified_at,
                   rs.verification_level,
                   rs.review_status
            FROM filtered_release rs
            JOIN LATERAL (
                SELECT snapshot.slug,
                       snapshot.canonical_title,
                       snapshot.cover_reference,
                       snapshot.cover_source,
                       snapshot.cover_usage_mode,
                       snapshot.cover_alternative_text,
                       snapshot.cover_source_url
                FROM catalogue.game_snapshot snapshot
                WHERE snapshot.publication_id = rs.publication_id
                  AND snapshot.game_id = rs.game_id
                LIMIT 1
            ) gs ON true
            JOIN catalogue.platform p ON p.platform_id = rs.platform_id
            JOIN catalogue.region r ON r.region_id = rs.region_id
            """;
    private static final String PUBLICATION_PREDICATE =
            "rs.publication_id = CAST(:publicationId AS uuid)";
    // Temporal classification is derived from the effective release period and the trusted
    // evaluation date (window bounds), never from a persisted 'released'/'scheduled' status that a
    // synchronization happened to write. Provider evidence only excludes: cancelled and delayed
    // never prove a recent release; cancelled is also excluded from upcoming, while a delayed
    // release with a valid future period stays relevant there.
    private static final String OCCURRED_BY_WINDOW_TO =
            "(rs.date_precision = 'day' AND rs.exact_date <= CAST(:windowTo AS date))"
                    + " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                    + " AND rs.period_end < CAST(:windowTo AS date))";
    private static final String OCCURRED_BY_WINDOW_FROM =
            "(rs.date_precision = 'day' AND rs.exact_date <= CAST(:windowFrom AS date))"
                    + " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                    + " AND rs.period_end < CAST(:windowFrom AS date))";
    private static final String KNOWN_PERIOD_OVERLAP =
            "rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                    + " AND daterange(rs.period_start, rs.period_end, '[]')"
                    + " && daterange(CAST(:windowFrom AS date), CAST(:windowTo AS date), '[]')";
    // Recent: a known date that has occurred, excluding cancelled and delayed. An unknown date has
    // no period, so it can never be recent regardless of any explicit released evidence.
    private static final String RECENT_PREDICATE =
            "rs.release_status NOT IN ('cancelled', 'delayed')"
                    + " AND ("
                    + OCCURRED_BY_WINDOW_TO
                    + ")"
                    + " AND "
                    + KNOWN_PERIOD_OVERLAP;
    // Upcoming (known date): a period that has not yet occurred, excluding cancelled. A delayed
    // release with a valid future period stays relevant; date governs, so an explicit released mark
    // does not exclude an unmet known date.
    private static final String UPCOMING_KNOWN_PREDICATE =
            "NOT (" + OCCURRED_BY_WINDOW_FROM + ")" + " AND " + KNOWN_PERIOD_OVERLAP;
    private static final String UPCOMING_PREDICATE =
            "rs.release_status <> 'cancelled' AND (" + UPCOMING_KNOWN_PREDICATE + ")";
    // TBA: an unknown date that is neither cancelled nor explicitly released.
    private static final String UPCOMING_OR_UNKNOWN_PREDICATE =
            "rs.release_status <> 'cancelled' AND (("
                    + UPCOMING_KNOWN_PREDICATE
                    + ") OR (rs.release_status <> 'released' AND rs.date_precision = 'unknown'))";
    private static final String PLATFORM_PREDICATE = "rs.platform_id = CAST(:platformId AS uuid)";
    private static final String REGION_PREDICATE = "rs.region_id = CAST(:regionId AS uuid)";
    private static final String RECENT_ORDER =
            " ORDER BY rs.period_end DESC NULLS LAST,"
                    + " lower(gs.canonical_title), rs.game_id, rs.release_id";
    // Upcoming lists the most precise dates first: exact day, then month, quarter, year, and
    // finally
    // TBA (unknown). Within a precision the soonest period comes first, ending in the unique
    // release
    // identifier for a deterministic total ordering.
    private static final String UPCOMING_PRECISION_ORDER =
            "CASE rs.date_precision"
                    + " WHEN 'day' THEN 1"
                    + " WHEN 'month' THEN 2"
                    + " WHEN 'quarter' THEN 3"
                    + " WHEN 'year' THEN 4"
                    + " ELSE 5 END";
    private static final String UPCOMING_ORDER =
            " ORDER BY "
                    + UPCOMING_PRECISION_ORDER
                    + ", rs.period_start ASC NULLS LAST,"
                    + " lower(gs.canonical_title), rs.game_id, rs.release_id";
    private static final String PAGE_SUFFIX = " LIMIT :pageSize OFFSET :offset";

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcReleaseBrowseReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> findPublishedReleases(Criteria criteria) {
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
        Optional<CurrentPublicationReader.Publication> currentPublication =
                CurrentPublicationReader.read(jdbcOperations);
        if (currentPublication.isEmpty()) {
            return Optional.empty();
        }
        CurrentPublicationReader.Publication publication = currentPublication.orElseThrow();
        List<Taxonomy> platforms =
                jdbcOperations.query(
                        PLATFORM_SQL,
                        Map.of(),
                        (resultSet, rowNumber) ->
                                new Taxonomy(
                                        resultSet.getString("platform_id"),
                                        resultSet.getString("display_name")));
        List<Taxonomy> regions =
                jdbcOperations.query(
                        REGION_SQL,
                        Map.of(),
                        (resultSet, rowNumber) ->
                                new Taxonomy(
                                        resultSet.getString("region_id"),
                                        resultSet.getString("display_name")));
        if (!supports(criteria.platformId(), platforms)
                || !supports(criteria.regionId(), regions)) {
            return Optional.of(new Result(publication.version(), platforms, regions, List.of(), 0));
        }
        Query query = query(publication.id(), criteria);
        Long totalItems =
                jdbcOperations.queryForObject(query.sql().count(), query.parameters(), Long.class);

        Map<String, Object> pageParameters = new LinkedHashMap<>(query.parameters());
        pageParameters.put("pageSize", criteria.pagination().pageSize());
        pageParameters.put("offset", criteria.pagination().offset());
        List<Item> items = jdbcOperations.query(query.sql().page(), pageParameters, this::mapItem);

        return Optional.of(
                new Result(
                        publication.version(),
                        platforms,
                        regions,
                        items,
                        totalItems == null ? 0 : totalItems));
    }

    private static boolean supports(String requestedId, List<Taxonomy> taxonomy) {
        return requestedId == null
                || taxonomy.stream().anyMatch(value -> value.id().equals(requestedId));
    }

    private static Query query(String publicationId, Criteria criteria) {
        CandidateQueryBuilder builder =
                new CandidateQueryBuilder()
                        .where(PUBLICATION_PREDICATE)
                        .bind("publicationId", publicationId);

        switch (criteria.view()) {
            case RECENT ->
                    builder.where(RECENT_PREDICATE)
                            .bind("windowFrom", criteria.window().from())
                            .bind("windowTo", criteria.window().to())
                            .orderBy(RECENT_ORDER);
            case UPCOMING ->
                    builder.where(
                                    criteria.includeUnknownUpcomingDates()
                                            ? UPCOMING_OR_UNKNOWN_PREDICATE
                                            : UPCOMING_PREDICATE)
                            .bind("windowFrom", criteria.window().from())
                            .bind("windowTo", criteria.window().to())
                            .orderBy(UPCOMING_ORDER);
        }

        return builder.whereIfPresent(PLATFORM_PREDICATE, "platformId", criteria.platformId())
                .whereIfPresent(REGION_PREDICATE, "regionId", criteria.regionId())
                .build();
    }

    private Item mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            return new Item(
                    resultSet.getString("release_id"),
                    resultSet.getString("game_id"),
                    resultSet.getString("slug"),
                    resultSet.getString("canonical_title"),
                    CatalogueCoverReferenceRowMapper.map(resultSet),
                    new Taxonomy(
                            resultSet.getString("platform_id"),
                            resultSet.getString("platform_name")),
                    new Taxonomy(
                            resultSet.getString("region_id"), resultSet.getString("region_name")),
                    ReleaseDateRowMapper.map(resultSet),
                    ReleaseStatus.fromValue(resultSet.getString("release_status")),
                    SourceKind.fromValue(resultSet.getString("source_kind")),
                    resultSet.getString("source_name"),
                    resultSet.getString("source_entity_type"),
                    instant(resultSet, "provider_updated_at"),
                    instant(resultSet, "last_synchronized_at"),
                    instant(resultSet, "last_verified_at"),
                    VerificationLevel.fromValue(resultSet.getString("verification_level")),
                    ReviewStatus.fromValue(resultSet.getString("review_status")));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }

    private static java.time.Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record Query(Sql sql, Map<String, Object> parameters) {}

    private record Sql(String count, String page) {}

    /** Composes trusted SQL predicates while keeping every external value as a bound parameter. */
    private static final class CandidateQueryBuilder {

        private final List<String> predicates = new ArrayList<>();
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private String orderBy;

        private CandidateQueryBuilder where(String predicate) {
            predicates.add(predicate);
            return this;
        }

        private CandidateQueryBuilder bind(String name, Object value) {
            if (parameters.putIfAbsent(name, value) != null) {
                throw new IllegalStateException("Duplicate release browse SQL parameter: " + name);
            }
            return this;
        }

        private CandidateQueryBuilder whereIfPresent(
                String predicate, String parameterName, Object value) {
            return value == null ? this : where(predicate).bind(parameterName, value);
        }

        private CandidateQueryBuilder orderBy(String order) {
            orderBy = order;
            return this;
        }

        private Query build() {
            if (predicates.isEmpty() || orderBy == null) {
                throw new IllegalStateException("Release browse query is incomplete");
            }
            String where = " WHERE " + String.join(" AND ", predicates);
            Sql sql =
                    new Sql(
                            COUNT_SELECT + where,
                            PAGE_PREFIX + where + ") " + PAGE_SELECT + orderBy + PAGE_SUFFIX);
            return new Query(sql, Map.copyOf(parameters));
        }
    }
}
