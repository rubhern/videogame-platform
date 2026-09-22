package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.View;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
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

/** PostgreSQL read adapter that returns only the requested release page and contextual facets. */
public final class JdbcReleaseBrowseReadAdapter implements ReleaseBrowseReadPort {

    // Grouping happens in PostgreSQL before pagination: filtered_release holds the releases that
    // match the view and active filters, count and page operate over distinct games, and the
    // releases of a paged game are re-joined and bounded so request memory stays
    // O(pageSize x releaseGroupLimit). Java never fetches releases and groups them afterwards.
    private static final String COUNT_SELECT =
            "SELECT count(DISTINCT rs.game_id) FROM catalogue.release_snapshot rs";
    private static final String FILTERED_RELEASE_PREFIX =
            "WITH filtered_release AS MATERIALIZED ("
                    + "SELECT * FROM catalogue.release_snapshot rs";
    private static final String GAME_SNAPSHOT_LATERAL =
            """
            JOIN LATERAL (
                SELECT snapshot.slug,
                       snapshot.canonical_title,
                       snapshot.cover_reference,
                       snapshot.cover_source,
                       snapshot.cover_usage_mode,
                       snapshot.cover_alternative_text,
                       snapshot.cover_source_url
                FROM catalogue.game_snapshot snapshot
                WHERE snapshot.publication_id = fr.publication_id
                  AND snapshot.game_id = fr.game_id
                LIMIT 1
            ) gs ON true
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
    // Multiple selected values inside one dimension combine with OR (ANY over the selected array);
    // the two dimensions combine with AND by appearing as separate predicates. The selected ids are
    // validated to exist before they reach the array cast, so the cast only ever sees real uuids.
    private static final String PLATFORM_PREDICATE =
            "rs.platform_id = ANY(CAST(:platformIds AS uuid[]))";
    private static final String REGION_PREDICATE =
            "rs.region_id = ANY(CAST(:regionIds AS uuid[]))";

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

        List<String> requestedPlatformIds = criteria.platformIds();
        List<String> requestedRegionIds = criteria.regionIds();
        // Only ids that exist in the product taxonomy are safe to cast to uuid[] and are eligible to
        // keep a selected value representable; an unknown id makes the whole request invalid (422).
        List<String> validPlatformIds = existing(requestedPlatformIds, "platform", "platform_id");
        List<String> validRegionIds = existing(requestedRegionIds, "region", "region_id");

        ViewPredicate view = viewPredicate(criteria);
        // Faceted availability: each dimension reflects the current window and the other dimension's
        // active selection, is never narrowed by its own selection, and always keeps a selected
        // valid value representable.
        List<Taxonomy> platforms =
                facet(
                        "platform",
                        "platform_id",
                        "region_id",
                        publication.id(),
                        view,
                        validRegionIds,
                        validPlatformIds);
        List<Taxonomy> regions =
                facet(
                        "region",
                        "region_id",
                        "platform_id",
                        publication.id(),
                        view,
                        validPlatformIds,
                        validRegionIds);

        boolean allValid =
                validPlatformIds.size() == requestedPlatformIds.size()
                        && validRegionIds.size() == requestedRegionIds.size();
        if (!allValid) {
            // An unknown selected id degrades to an empty page; the application maps it to 422 by
            // finding the requested id absent from the returned facets.
            return Optional.of(new Result(publication.version(), platforms, regions, List.of(), 0));
        }

        Query query = query(publication.id(), criteria, view, validPlatformIds, validRegionIds);
        Long totalItems =
                jdbcOperations.queryForObject(query.sql().count(), query.parameters(), Long.class);

        Map<String, Object> pageParameters = new LinkedHashMap<>(query.parameters());
        pageParameters.put("pageSize", criteria.pagination().pageSize());
        pageParameters.put("offset", criteria.pagination().offset());
        pageParameters.put("releaseGroupLimit", criteria.releaseGroupLimit());
        List<Item> items =
                jdbcOperations.query(
                        query.sql().page(), pageParameters, ReleaseGroupPageMapper::map);

        return Optional.of(
                new Result(
                        publication.version(),
                        platforms,
                        regions,
                        items,
                        totalItems == null ? 0 : totalItems));
    }

    /** The subset of the requested ids that exist in the product taxonomy, compared as text. */
    private List<String> existing(List<String> ids, String table, String idColumn) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcOperations.query(
                "SELECT "
                        + idColumn
                        + "::text FROM catalogue."
                        + table
                        + " WHERE "
                        + idColumn
                        + "::text IN (:ids)",
                Map.of("ids", ids),
                (resultSet, rowNumber) -> resultSet.getString(1));
    }

    /**
     * One dimension's contextual options: every taxonomy value that either is a currently selected
     * valid value or appears in a release matching the current window and the other dimension's
     * active selection.
     */
    private List<Taxonomy> facet(
            String table,
            String dimensionColumn,
            String otherDimensionColumn,
            String publicationId,
            ViewPredicate view,
            List<String> otherDimensionIds,
            List<String> ownSelectedIds) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("publicationId", publicationId);
        parameters.putAll(view.parameters());

        StringBuilder contextual =
                new StringBuilder(
                        "t."
                                + dimensionColumn
                                + " IN (SELECT rs."
                                + dimensionColumn
                                + " FROM catalogue.release_snapshot rs WHERE "
                                + PUBLICATION_PREDICATE
                                + " AND ("
                                + view.predicate()
                                + ")");
        if (!otherDimensionIds.isEmpty()) {
            contextual
                    .append(" AND rs.")
                    .append(otherDimensionColumn)
                    .append(" = ANY(CAST(:otherIds AS uuid[]))");
            parameters.put("otherIds", arrayLiteral(otherDimensionIds));
        }
        contextual.append(")");

        List<String> branches = new ArrayList<>();
        if (!ownSelectedIds.isEmpty()) {
            branches.add("t." + dimensionColumn + " = ANY(CAST(:ownSelected AS uuid[]))");
            parameters.put("ownSelected", arrayLiteral(ownSelectedIds));
        }
        branches.add(contextual.toString());

        String sql =
                "SELECT t."
                        + dimensionColumn
                        + "::text AS id, t.display_name AS name FROM catalogue."
                        + table
                        + " t WHERE "
                        + String.join(" OR ", branches);
        return jdbcOperations.query(
                sql,
                parameters,
                (resultSet, rowNumber) ->
                        new Taxonomy(resultSet.getString("id"), resultSet.getString("name")));
    }

    private static ViewPredicate viewPredicate(Criteria criteria) {
        String predicate =
                switch (criteria.view()) {
                    case RECENT -> RECENT_PREDICATE;
                    case UPCOMING ->
                            criteria.includeUnknownUpcomingDates()
                                    ? UPCOMING_OR_UNKNOWN_PREDICATE
                                    : UPCOMING_PREDICATE;
                };
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("windowFrom", criteria.window().from());
        parameters.put("windowTo", criteria.window().to());
        return new ViewPredicate(predicate, parameters);
    }

    /** Comma-separated PostgreSQL array literal of validated uuids: safe because they are real ids. */
    private static String arrayLiteral(List<String> uuids) {
        return "{" + String.join(",", uuids) + "}";
    }

    private static Query query(
            String publicationId,
            Criteria criteria,
            ViewPredicate view,
            List<String> platformIds,
            List<String> regionIds) {
        CandidateQueryBuilder builder =
                new CandidateQueryBuilder()
                        .where(PUBLICATION_PREDICATE)
                        .bind("publicationId", publicationId)
                        .where(view.predicate());
        view.parameters().forEach(builder::bind);
        if (!platformIds.isEmpty()) {
            builder.where(PLATFORM_PREDICATE).bind("platformIds", arrayLiteral(platformIds));
        }
        if (!regionIds.isEmpty()) {
            builder.where(REGION_PREDICATE).bind("regionIds", arrayLiteral(regionIds));
        }
        return builder.build(criteria.view());
    }

    // A game's precise date, exact day first through TBA (unknown) last, drives the upcoming order.
    private static String precisionRank(String alias) {
        return "CASE "
                + alias
                + ".date_precision WHEN 'day' THEN 1 WHEN 'month' THEN 2"
                + " WHEN 'quarter' THEN 3 WHEN 'year' THEN 4 ELSE 5 END";
    }

    /** Ordering of the releases inside one game; ends in the unique release id. */
    private static String releaseOrder(View view, String alias) {
        return switch (view) {
            case RECENT -> alias + ".period_end DESC NULLS LAST, " + alias + ".release_id";
            case UPCOMING ->
                    precisionRank(alias)
                            + ", "
                            + alias
                            + ".period_start ASC NULLS LAST, "
                            + alias
                            + ".release_id";
        };
    }

    /** Ordering of the games by their first relevant release; ends in the unique game id. */
    private static String gameOrder(View view, String alias) {
        return switch (view) {
            case RECENT ->
                    alias
                            + ".period_end DESC NULLS LAST, lower("
                            + alias
                            + ".canonical_title), "
                            + alias
                            + ".game_id";
            case UPCOMING ->
                    precisionRank(alias)
                            + ", "
                            + alias
                            + ".period_start ASC NULLS LAST, lower("
                            + alias
                            + ".canonical_title), "
                            + alias
                            + ".game_id";
        };
    }

    private static String pageSql(View view, String where) {
        return FILTERED_RELEASE_PREFIX
                + where
                + "),\n"
                + "game_top AS (\n"
                + "    SELECT DISTINCT ON (fr.game_id)\n"
                + "        fr.game_id, fr.period_end, fr.period_start, fr.date_precision,\n"
                + "        gs.slug, gs.canonical_title, gs.cover_reference, gs.cover_source,\n"
                + "        gs.cover_usage_mode, gs.cover_alternative_text, gs.cover_source_url\n"
                + "    FROM filtered_release fr\n"
                + GAME_SNAPSHOT_LATERAL
                + "    ORDER BY fr.game_id, "
                + releaseOrder(view, "fr")
                + "\n),\n"
                + "game_page AS (\n"
                + "    SELECT * FROM game_top gt\n"
                + "    ORDER BY "
                + gameOrder(view, "gt")
                + "\n    LIMIT :pageSize OFFSET :offset\n)\n"
                + "SELECT gp.game_id::text AS game_id,\n"
                + "       gp.slug AS slug,\n"
                + "       gp.canonical_title AS canonical_title,\n"
                + "       gp.cover_reference, gp.cover_source, gp.cover_usage_mode,\n"
                + "       gp.cover_alternative_text, gp.cover_source_url,\n"
                + "       rel.release_id::text AS release_id,\n"
                + "       rel.platform_id::text AS platform_id, rel.platform_name AS platform_name,\n"
                + "       rel.region_id::text AS region_id, rel.region_name AS region_name,\n"
                + "       rel.date_precision, rel.exact_date, rel.release_year, rel.release_month,\n"
                + "       rel.release_quarter, rel.release_status, rel.source_kind, rel.source_name,\n"
                + "       rel.source_entity_type, rel.provider_updated_at, rel.last_synchronized_at,\n"
                + "       rel.last_verified_at, rel.verification_level, rel.review_status\n"
                + "FROM game_page gp\n"
                + "JOIN LATERAL (\n"
                + "    SELECT fr.release_id, fr.platform_id, fr.region_id,\n"
                + "           p.display_name AS platform_name, r.display_name AS region_name,\n"
                + "           fr.date_precision, fr.exact_date, fr.release_year, fr.release_month,\n"
                + "           fr.release_quarter, fr.release_status, fr.source_kind, fr.source_name,\n"
                + "           fr.source_entity_type, fr.provider_updated_at, fr.last_synchronized_at,\n"
                + "           fr.last_verified_at, fr.verification_level, fr.review_status,\n"
                + "           fr.period_end, fr.period_start\n"
                + "    FROM filtered_release fr\n"
                + "    JOIN catalogue.platform p ON p.platform_id = fr.platform_id\n"
                + "    JOIN catalogue.region r ON r.region_id = fr.region_id\n"
                + "    WHERE fr.game_id = gp.game_id\n"
                + "    ORDER BY "
                + releaseOrder(view, "fr")
                + "\n    LIMIT :releaseGroupLimit\n"
                + ") rel ON true\n"
                + "ORDER BY "
                + gameOrder(view, "gp")
                + ", "
                + releaseOrder(view, "rel");
    }

    private record ViewPredicate(String predicate, Map<String, Object> parameters) {}

    private record Query(Sql sql, Map<String, Object> parameters) {}

    private record Sql(String count, String page) {}

    /** Composes trusted SQL predicates while keeping every external value as a bound parameter. */
    private static final class CandidateQueryBuilder {

        private final List<String> predicates = new ArrayList<>();
        private final Map<String, Object> parameters = new LinkedHashMap<>();

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

        private Query build(View view) {
            if (predicates.isEmpty()) {
                throw new IllegalStateException("Release browse query is incomplete");
            }
            String where = " WHERE " + String.join(" AND ", predicates);
            Sql sql = new Sql(COUNT_SELECT + where, pageSql(view, where));
            return new Query(sql, Map.copyOf(parameters));
        }
    }
}
