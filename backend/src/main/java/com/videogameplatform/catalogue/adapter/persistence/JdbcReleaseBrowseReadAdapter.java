package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private static final String RECENT_WHERE =
            " WHERE rs.publication_id = ?::uuid"
                    + " AND rs.release_status = 'released'"
                    + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                    + " AND daterange(rs.period_start, rs.period_end, '[]')"
                    + " && daterange(?::date, ?::date, '[]')";
    private static final String UPCOMING_WHERE =
            " WHERE rs.publication_id = ?::uuid"
                    + " AND rs.release_status NOT IN ('released', 'cancelled')"
                    + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                    + " AND daterange(rs.period_start, rs.period_end, '[]')"
                    + " && daterange(?::date, ?::date, '[]')";
    private static final String UPCOMING_WITH_UNKNOWN_WHERE =
            " WHERE rs.publication_id = ?::uuid"
                    + " AND rs.release_status NOT IN ('released', 'cancelled') AND ("
                    + "(rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                    + " AND daterange(rs.period_start, rs.period_end, '[]')"
                    + " && daterange(?::date, ?::date, '[]'))"
                    + " OR rs.date_precision = 'unknown')";
    private static final String PLATFORM_FILTER = " AND rs.platform_id = ?::uuid";
    private static final String REGION_FILTER = " AND rs.region_id = ?::uuid";
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
        } catch (DataAccessException exception) {
            throw new CatalogueReadException(exception);
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
        List<Object> parameters = new ArrayList<>();
        parameters.add(publicationId);
        parameters.add(criteria.window().from());
        parameters.add(criteria.window().to());
        if (criteria.platformId() != null) {
            parameters.add(criteria.platformId());
        }
        if (criteria.regionId() != null) {
            parameters.add(criteria.regionId());
        }
        return new Query(sql(criteria), List.copyOf(parameters));
    }

    private static Sql sql(Criteria criteria) {
        int filters =
                (criteria.platformId() == null ? 0 : 2) + (criteria.regionId() == null ? 0 : 1);
        if (criteria.view() == BrowseReleasesUseCase.View.RECENT) {
            return switch (filters) {
                case 0 -> RECENT_SQL;
                case 1 -> RECENT_REGION_SQL;
                case 2 -> RECENT_PLATFORM_SQL;
                case 3 -> RECENT_PLATFORM_REGION_SQL;
                default ->
                        throw new IllegalStateException("Unsupported release filter combination");
            };
        }
        if (criteria.includeUnknownUpcomingDates()) {
            return switch (filters) {
                case 0 -> UPCOMING_WITH_UNKNOWN_SQL;
                case 1 -> UPCOMING_WITH_UNKNOWN_REGION_SQL;
                case 2 -> UPCOMING_WITH_UNKNOWN_PLATFORM_SQL;
                case 3 -> UPCOMING_WITH_UNKNOWN_PLATFORM_REGION_SQL;
                default ->
                        throw new IllegalStateException("Unsupported release filter combination");
            };
        }
        return switch (filters) {
            case 0 -> UPCOMING_SQL;
            case 1 -> UPCOMING_REGION_SQL;
            case 2 -> UPCOMING_PLATFORM_SQL;
            case 3 -> UPCOMING_PLATFORM_REGION_SQL;
            default -> throw new IllegalStateException("Unsupported release filter combination");
        };
    }

    private Item mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Item(
                resultSet.getString("release_id"),
                resultSet.getString("game_id"),
                resultSet.getString("slug"),
                resultSet.getString("canonical_title"),
                cover(resultSet),
                new Taxonomy(
                        resultSet.getString("platform_id"), resultSet.getString("platform_name")),
                new Taxonomy(resultSet.getString("region_id"), resultSet.getString("region_name")),
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
    }

    private static CoverReference cover(ResultSet resultSet) throws SQLException {
        String usageMode = resultSet.getString("cover_usage_mode");
        String alternativeText = resultSet.getString("cover_alternative_text");
        if ("product_owned".equals(usageMode)) {
            return new ProductCoverReference(
                    resultSet.getString("cover_reference"), alternativeText);
        }
        String sourceUrl = resultSet.getString("cover_source_url");
        if (sourceUrl == null) {
            return new UnavailableCoverReference(alternativeText);
        }
        return new ProviderCoverReference(
                resultSet.getString("cover_source"),
                resultSet.getString("cover_reference"),
                alternativeText,
                sourceUrl);
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
            default -> throw new SQLException("Unsupported persisted date precision");
        };
    }

    private static java.time.Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record Publication(String id, String version) {}

    private record Query(Sql sql, List<Object> parameters) {}

    private record Sql(String count, String page) {

        private static Sql from(String where, String order) {
            return new Sql(
                    COUNT_SELECT + where,
                    PAGE_PREFIX + where + ") " + PAGE_SELECT + order + PAGE_SUFFIX);
        }
    }

    private static final Sql RECENT_SQL = Sql.from(RECENT_WHERE, RECENT_ORDER);
    private static final Sql RECENT_REGION_SQL =
            Sql.from(RECENT_WHERE + REGION_FILTER, RECENT_ORDER);
    private static final Sql RECENT_PLATFORM_SQL =
            Sql.from(RECENT_WHERE + PLATFORM_FILTER, RECENT_ORDER);
    private static final Sql RECENT_PLATFORM_REGION_SQL =
            Sql.from(RECENT_WHERE + PLATFORM_FILTER + REGION_FILTER, RECENT_ORDER);
    private static final Sql UPCOMING_SQL = Sql.from(UPCOMING_WHERE, UPCOMING_ORDER);
    private static final Sql UPCOMING_REGION_SQL =
            Sql.from(UPCOMING_WHERE + REGION_FILTER, UPCOMING_ORDER);
    private static final Sql UPCOMING_PLATFORM_SQL =
            Sql.from(UPCOMING_WHERE + PLATFORM_FILTER, UPCOMING_ORDER);
    private static final Sql UPCOMING_PLATFORM_REGION_SQL =
            Sql.from(UPCOMING_WHERE + PLATFORM_FILTER + REGION_FILTER, UPCOMING_ORDER);
    private static final Sql UPCOMING_WITH_UNKNOWN_SQL =
            Sql.from(UPCOMING_WITH_UNKNOWN_WHERE, UPCOMING_ORDER);
    private static final Sql UPCOMING_WITH_UNKNOWN_REGION_SQL =
            Sql.from(UPCOMING_WITH_UNKNOWN_WHERE + REGION_FILTER, UPCOMING_ORDER);
    private static final Sql UPCOMING_WITH_UNKNOWN_PLATFORM_SQL =
            Sql.from(UPCOMING_WITH_UNKNOWN_WHERE + PLATFORM_FILTER, UPCOMING_ORDER);
    private static final Sql UPCOMING_WITH_UNKNOWN_PLATFORM_REGION_SQL =
            Sql.from(UPCOMING_WITH_UNKNOWN_WHERE + PLATFORM_FILTER + REGION_FILTER, UPCOMING_ORDER);
}
