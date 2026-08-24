package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL read adapter that returns only the requested release page. */
final class JdbcReleaseBrowseReadAdapter implements ReleaseBrowseReadPort {

    private static final String CURRENT_PUBLICATION_SQL =
            """
            SELECT publication_id::text, catalogue_version
            FROM catalogue.catalogue_publication
            WHERE is_current
            """;
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
    private static final String PUBLICATION_PREDICATE = "rs.publication_id = ?::uuid";
    private static final String RELEASED_PREDICATE = "rs.release_status = 'released'";
    private static final String UPCOMING_STATUS_PREDICATE =
            "rs.release_status NOT IN ('released', 'cancelled')";
    private static final String KNOWN_PERIOD_OVERLAP_PREDICATE =
            "rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                    + " AND daterange(rs.period_start, rs.period_end, '[]')"
                    + " && daterange(?::date, ?::date, '[]')";
    private static final String PERIOD_OVERLAP_OR_UNKNOWN_PREDICATE =
            "((rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                    + " AND daterange(rs.period_start, rs.period_end, '[]')"
                    + " && daterange(?::date, ?::date, '[]'))"
                    + " OR rs.date_precision = 'unknown')";
    private static final String PLATFORM_PREDICATE = "rs.platform_id = ?::uuid";
    private static final String REGION_PREDICATE = "rs.region_id = ?::uuid";
    private static final String RECENT_ORDER =
            " ORDER BY rs.period_end DESC NULLS LAST,"
                    + " lower(gs.canonical_title), rs.game_id, rs.release_id";
    private static final String UPCOMING_ORDER =
            " ORDER BY rs.period_start ASC NULLS LAST,"
                    + " lower(gs.canonical_title), rs.game_id, rs.release_id";
    private static final String PAGE_SUFFIX = " LIMIT ? OFFSET ?";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate readTransaction;

    JdbcReleaseBrowseReadAdapter(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Optional<Result> findPublishedReleases(Criteria criteria) {
        try {
            return readTransaction.execute(status -> findInTransaction(criteria));
        } catch (CannotCreateTransactionException | DataAccessResourceFailureException |
                 RecoverableDataAccessException | TransientDataAccessException exception) {
            throw new CatalogueReadException(exception);
        }  catch (DataAccessException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }

    private Optional<Result> findInTransaction(Criteria criteria) {
        Optional<Publication> currentPublication =
                jdbcTemplate.query(
                        CURRENT_PUBLICATION_SQL,
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
        if (currentPublication.isEmpty()) {
            return Optional.empty();
        }
        Publication publication = currentPublication.orElseThrow();
        List<Taxonomy> platforms =
                jdbcTemplate.query(
                        PLATFORM_SQL,
                        (resultSet, rowNumber) ->
                                new Taxonomy(
                                        resultSet.getString("platform_id"),
                                        resultSet.getString("display_name")));
        List<Taxonomy> regions =
                jdbcTemplate.query(
                        REGION_SQL,
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
                jdbcTemplate.queryForObject(
                        query.sql().count(), Long.class, query.parameters().toArray());

        List<Object> pageParameters = new ArrayList<>(query.parameters());
        pageParameters.add(criteria.pagination().pageSize());
        pageParameters.add(criteria.pagination().offset());
        List<Item> items =
                jdbcTemplate.query(query.sql().page(), this::mapItem, pageParameters.toArray());

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
                new CandidateQueryBuilder().where(PUBLICATION_PREDICATE, publicationId);

        switch (criteria.view()) {
            case RECENT ->
                    builder.where(RELEASED_PREDICATE)
                            .where(
                                    KNOWN_PERIOD_OVERLAP_PREDICATE,
                                    criteria.window().from(),
                                    criteria.window().to())
                            .orderBy(RECENT_ORDER);
            case UPCOMING ->
                    builder.where(UPCOMING_STATUS_PREDICATE)
                            .where(
                                    criteria.includeUnknownUpcomingDates()
                                            ? PERIOD_OVERLAP_OR_UNKNOWN_PREDICATE
                                            : KNOWN_PERIOD_OVERLAP_PREDICATE,
                                    criteria.window().from(),
                                    criteria.window().to())
                            .orderBy(UPCOMING_ORDER);
        }

        return builder.whereIfPresent(PLATFORM_PREDICATE, criteria.platformId())
                .whereIfPresent(REGION_PREDICATE, criteria.regionId())
                .build();
    }

    private Item mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            return new Item(
                    resultSet.getString("release_id"),
                    resultSet.getString("game_id"),
                    resultSet.getString("slug"),
                    resultSet.getString("canonical_title"),
                    cover(resultSet),
                    new Taxonomy(
                            resultSet.getString("platform_id"),
                            resultSet.getString("platform_name")),
                    new Taxonomy(
                            resultSet.getString("region_id"), resultSet.getString("region_name")),
                    releaseDate(resultSet),
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

    private static CoverReference cover(ResultSet resultSet) throws SQLException {
        String usageMode = resultSet.getString("cover_usage_mode");
        String alternativeText = resultSet.getString("cover_alternative_text");
        return switch (usageMode) {
            case "product_owned" ->
                    new ProductCoverReference(
                            resultSet.getString("cover_reference"), alternativeText);
            case "provider_cdn_reference" -> {
                String sourceUrl = resultSet.getString("cover_source_url");
                yield sourceUrl == null
                        ? new UnavailableCoverReference(alternativeText)
                        : new ProviderCoverReference(
                                resultSet.getString("cover_source"),
                                resultSet.getString("cover_reference"),
                                alternativeText,
                                sourceUrl);
            }
            default -> throw new IllegalArgumentException("Unsupported persisted cover usage mode");
        };
    }

    private static ReleaseDate releaseDate(ResultSet resultSet) throws SQLException {
        return switch (resultSet.getString("date_precision")) {
            case "day" ->
                    new ReleaseDate.Day(
                            resultSet.getObject("exact_date", java.time.LocalDate.class));
            case "month" ->
                    new ReleaseDate.Month(
                            YearMonth.of(
                                    resultSet.getInt("release_year"),
                                    resultSet.getInt("release_month")));
            case "quarter" ->
                    new ReleaseDate.Quarter(
                            resultSet.getInt("release_year"), resultSet.getInt("release_quarter"));
            case "year" -> new ReleaseDate.YearOnly(Year.of(resultSet.getInt("release_year")));
            case "unknown" -> new ReleaseDate.Unknown();
            default -> throw new IllegalArgumentException("Unsupported persisted date precision");
        };
    }

    private static java.time.Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record Publication(String id, String version) {}

    private record Query(Sql sql, List<Object> parameters) {}

    private record Sql(String count, String page) {}

    /** Composes trusted SQL predicates while keeping every external value as a bound parameter. */
    private static final class CandidateQueryBuilder {

        private final List<String> predicates = new ArrayList<>();
        private final List<Object> parameters = new ArrayList<>();
        private String orderBy;

        private CandidateQueryBuilder where(String predicate, Object... values) {
            predicates.add(predicate);
            parameters.addAll(List.of(values));
            return this;
        }

        private CandidateQueryBuilder whereIfPresent(String predicate, Object value) {
            return value == null ? this : where(predicate, value);
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
            return new Query(sql, List.copyOf(parameters));
        }
    }
}
