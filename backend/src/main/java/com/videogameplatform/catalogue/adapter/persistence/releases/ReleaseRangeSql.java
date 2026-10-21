package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/**
 * The release explorer's range rules as SQL, shared by its page read and the release calendar's
 * counts so both classify every release identically: the trusted evaluation date, the precision
 * rules, the multi-select filters and the contextual facets.
 */
final class ReleaseRangeSql {

    static final String PUBLICATION_PREDICATE = "rs.publication_id = CAST(:publicationId AS uuid)";
    // Multiple selected values inside one dimension combine with OR (ANY over the selected array);
    // the two dimensions combine with AND by appearing as separate predicates. The selected ids are
    // validated to exist before they reach the array cast, so the cast only ever sees real uuids.
    static final String PLATFORM_PREDICATE = "rs.platform_id = ANY(CAST(:platformIds AS uuid[]))";
    static final String REGION_PREDICATE = "rs.region_id = ANY(CAST(:regionIds AS uuid[]))";
    static final String PLATFORM_ID_COLUMN = "platform_id";
    static final String REGION_ID_COLUMN = "region_id";

    // Temporal classification is derived from the effective release period and the trusted
    // evaluation date, never from a persisted 'released'/'scheduled' status that a synchronization
    // happened to write. Every candidate has a known period overlapping the range and is not
    // cancelled; an unknown (TBA) date has no period, so it never belongs to a range. Up to and
    // including the evaluation date a release must have happened: an exact day, or a month,
    // quarter or year period that has ended, and delayed evidence excludes it. After the
    // evaluation date an exact day is expected, so a delayed release there stays relevant. The
    // overlap keeps the shared period index (ix_release_browse_period) bounding the scan.
    private static final String RANGE_PREDICATE =
            "rs.release_status <> 'cancelled'"
                    + " AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL"
                    + " AND daterange(rs.period_start, rs.period_end, '[]')"
                    + " && daterange(CAST(:rangeFrom AS date), CAST(:rangeTo AS date), '[]')"
                    + " AND ((rs.date_precision = 'day'"
                    + " AND (rs.exact_date > CAST(:evaluatedOn AS date)"
                    + " OR rs.release_status <> 'delayed'))"
                    + " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                    + " AND rs.period_end < CAST(:evaluatedOn AS date)"
                    + " AND rs.release_status <> 'delayed')";
    // Approximate-date opt-in: a known month, quarter or year period that has not ended joins.
    // It is sent only for a range reaching the evaluation date, so the overlap above already
    // places it in the range from today on. Each row keeps its stored precision; no exact day is
    // ever derived for a partial date.
    private static final String APPROXIMATE_BRANCH =
            " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                    + " AND rs.period_end >= CAST(:evaluatedOn AS date))";

    private ReleaseRangeSql() {}

    record RangePredicate(String predicate, Map<String, Object> parameters) {}

    static RangePredicate range(
            LocalDate from, LocalDate to, LocalDate evaluatedOn, boolean includeApproximateDates) {
        String predicate =
                RANGE_PREDICATE + (includeApproximateDates ? APPROXIMATE_BRANCH : "") + ")";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("rangeFrom", from);
        parameters.put("rangeTo", to);
        parameters.put("evaluatedOn", evaluatedOn);
        return new RangePredicate(predicate, parameters);
    }

    /** Filter before facets/count/page; EXISTS preserves release cardinality and excludes missing evidence. */
    static RangePredicate highlights(RangePredicate range) {
        Map<String, Object> parameters = new LinkedHashMap<>(range.parameters());
        parameters.put(
                "minimumHypes",
                com.videogameplatform.catalogue.application.releases.ReleaseHighlightsPolicy
                        .MINIMUM_HYPES);
        return new RangePredicate(
                "("
                        + range.predicate()
                        + ") AND EXISTS ("
                        + "SELECT 1 FROM catalogue.game_featured_evidence highlight"
                        + " WHERE highlight.game_id = rs.game_id AND highlight.hypes >= :minimumHypes)",
                parameters);
    }

    /** The subset of the requested ids that exist in the product taxonomy, compared as text. */
    static List<String> existing(
            NamedParameterJdbcOperations jdbcOperations,
            List<String> ids,
            String table,
            String idColumn) {
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
     * valid value or appears in a release matching the current range and the other dimension's
     * active selection.
     */
    static List<Taxonomy> facet(
            NamedParameterJdbcOperations jdbcOperations,
            String table,
            String dimensionColumn,
            String otherDimensionColumn,
            String publicationId,
            RangePredicate range,
            List<String> otherDimensionIds,
            List<String> ownSelectedIds) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("publicationId", publicationId);
        parameters.putAll(range.parameters());

        StringBuilder contextual =
                new StringBuilder(
                        "t."
                                + dimensionColumn
                                + " IN (SELECT rs."
                                + dimensionColumn
                                + " FROM catalogue.release_snapshot rs WHERE "
                                + PUBLICATION_PREDICATE
                                + " AND ("
                                + range.predicate()
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

    /** Comma-separated PostgreSQL array literal of validated uuids: safe because they are real ids. */
    static String arrayLiteral(List<String> uuids) {
        return "{" + String.join(",", uuids) + "}";
    }
}
