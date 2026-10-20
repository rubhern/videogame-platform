package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueGenresReader;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.adapter.persistence.ReleasePresentationOrder;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL read adapter that returns only the requested release page and contextual facets. */
public final class JdbcReleaseBrowseReadAdapter implements ReleaseBrowseReadPort {

    // Grouping happens in PostgreSQL before pagination: filtered_release holds the releases that
    // match the range and active filters, presented_release keeps the one presented release of each
    // game and platform among them, count and page operate over distinct games, and the presented
    // releases of a paged game are re-joined and bounded so request memory stays
    // O(pageSize x releaseGroupLimit). Java never fetches releases and groups them afterwards.
    // Every game with a filtered release keeps at least one presented release, so the count over
    // filtered releases is also the count of presented games.
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
    // The application sends it only for a range reaching the evaluation date, so the overlap above
    // already places it in the range from today on. Each row keeps its stored precision; no exact
    // day is ever derived for a partial date.
    private static final String APPROXIMATE_BRANCH =
            " OR (rs.date_precision IN ('month', 'quarter', 'year')"
                    + " AND rs.period_end >= CAST(:evaluatedOn AS date))";
    // Multiple selected values inside one dimension combine with OR (ANY over the selected array);
    // the two dimensions combine with AND by appearing as separate predicates. The selected ids are
    // validated to exist before they reach the array cast, so the cast only ever sees real uuids.
    private static final String PLATFORM_PREDICATE =
            "rs.platform_id = ANY(CAST(:platformIds AS uuid[]))";
    private static final String REGION_PREDICATE = "rs.region_id = ANY(CAST(:regionIds AS uuid[]))";
    private static final String PLATFORM_ID_COLUMN = "platform_id";
    private static final String REGION_ID_COLUMN = "region_id";

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcReleaseBrowseReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> findPublishedReleases(Criteria criteria) {
        return CatalogueReadTransaction.execute(
                readTransaction, status -> findInTransaction(criteria));
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
        // Only ids that exist in the product taxonomy are safe to cast to uuid[] and are eligible
        // to
        // keep a selected value representable; an unknown id makes the whole request invalid (422).
        List<String> validPlatformIds = existing(requestedPlatformIds, "platform", "platform_id");
        List<String> validRegionIds = existing(requestedRegionIds, "region", "region_id");

        RangePredicate range = rangePredicate(criteria);
        // Faceted availability: each dimension reflects the current range and the other
        // dimension's active selection, is never narrowed by its own selection, and always keeps a
        // selected
        // valid value representable.
        List<Taxonomy> platforms =
                facet(
                        "platform",
                        PLATFORM_ID_COLUMN,
                        REGION_ID_COLUMN,
                        publication.id(),
                        range,
                        validRegionIds,
                        validPlatformIds);
        List<Taxonomy> regions =
                facet(
                        "region",
                        REGION_ID_COLUMN,
                        PLATFORM_ID_COLUMN,
                        publication.id(),
                        range,
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

        Query query = query(publication.id(), criteria, range, validPlatformIds, validRegionIds);
        Long totalItems =
                jdbcOperations.queryForObject(query.sql().count(), query.parameters(), Long.class);

        Map<String, Object> pageParameters = new LinkedHashMap<>(query.parameters());
        pageParameters.put("pageSize", criteria.pagination().pageSize());
        pageParameters.put("offset", criteria.pagination().offset());
        pageParameters.put("releaseGroupLimit", criteria.releaseGroupLimit());
        List<Item> items =
                jdbcOperations.query(
                        query.sql().page(), pageParameters, ReleaseGroupPageMapper::map);

        var genres =
                CatalogueGenresReader.read(
                        jdbcOperations,
                        publication.id(),
                        items.stream().map(Item::gameId).toList(),
                        2);
        items =
                items.stream()
                        .map(
                                item ->
                                        new Item(
                                                item.gameId(),
                                                item.slug(),
                                                item.canonicalTitle(),
                                                item.cover(),
                                                item.releases(),
                                                genres.getOrDefault(item.gameId(), List.of())))
                        .toList();
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

    private static RangePredicate rangePredicate(Criteria criteria) {
        String predicate =
                RANGE_PREDICATE
                        + (criteria.includeApproximateDates() ? APPROXIMATE_BRANCH : "")
                        + ")";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("rangeFrom", criteria.window().from());
        parameters.put("rangeTo", criteria.window().to());
        parameters.put("evaluatedOn", criteria.evaluatedOn());
        return new RangePredicate(predicate, parameters);
    }

    /** Comma-separated PostgreSQL array literal of validated uuids: safe because they are real ids. */
    private static String arrayLiteral(List<String> uuids) {
        return "{" + String.join(",", uuids) + "}";
    }

    private static Query query(
            String publicationId,
            Criteria criteria,
            RangePredicate range,
            List<String> platformIds,
            List<String> regionIds) {
        CandidateQueryBuilder builder =
                new CandidateQueryBuilder()
                        .where(PUBLICATION_PREDICATE)
                        .bind("publicationId", publicationId)
                        .where(range.predicate());
        range.parameters().forEach(builder::bind);
        if (!platformIds.isEmpty()) {
            builder.where(PLATFORM_PREDICATE).bind("platformIds", arrayLiteral(platformIds));
        }
        if (!regionIds.isEmpty()) {
            builder.where(REGION_PREDICATE).bind("regionIds", arrayLiteral(regionIds));
        }
        return builder.build(criteria.sort());
    }

    /**
     * The ascending date order: the last day of the known period, which is the day itself for an
     * exact date, then an exact day before a month, quarter or year ending the same day. It never
     * places a partial date on an invented day.
     */
    private static String dateOrder(String alias) {
        return alias + ".period_end ASC, " + ReleasePresentationOrder.precisionRank(alias);
    }

    /** Ordering of the releases inside one game; ends in the unique release id. */
    private static String releaseOrder(String alias) {
        return dateOrder(alias) + ", " + alias + ".release_id";
    }

    /**
     * Ordering of the games, each positioned by its first presented release; ends in the unique
     * game id. Hype and rating lead when chosen, and a game without the signal follows every game
     * with one rather than counting as zero.
     */
    private static String gameOrder(Sort sort, String alias) {
        String dateOrder =
                dateOrder(alias) + ", lower(" + alias + ".canonical_title), " + alias + ".game_id";
        return switch (sort) {
            case RELEASE_DATE -> dateOrder;
            case HYPE -> alias + ".hypes DESC NULLS LAST, " + dateOrder;
            case RATING ->
                    alias
                            + ".community_mean DESC NULLS LAST, "
                            + alias
                            + ".community_rating_count DESC NULLS LAST, "
                            + dateOrder;
            case EXTERNAL_PLAYER_SCORE ->
                    alias
                            + ".player_score DESC NULLS LAST, "
                            + alias
                            + ".player_score_count DESC NULLS LAST, "
                            + dateOrder;
        };
    }

    // The presented release of a game and platform is the first of its filtered releases under the
    // shared presentation precedence, which ends in the earliest date. Only presented releases
    // position, group and fill the page; the others stay stored and in game details. The order's
    // signals join Catalogue-owned tables only: the Hypes evidence TOPómetro ranks with, the
    // stored external player score and the community score projection Ratings maintains through
    // a Catalogue contract (ADR-0029).
    private static String pageSql(Sort sort, String where) {
        return FILTERED_RELEASE_PREFIX
                + where
                + "),\n"
                + "presented_release AS MATERIALIZED (\n"
                + "    SELECT DISTINCT ON (fr.game_id, fr.platform_id) fr.*\n"
                + "    FROM filtered_release fr\n"
                + "    JOIN catalogue.region release_region"
                + " ON release_region.region_id = fr.region_id\n"
                + "    ORDER BY fr.game_id, fr.platform_id, "
                + ReleasePresentationOrder.of("fr", "release_region", dateOrder("fr"))
                + "\n),\n"
                + "game_top AS (\n"
                + "    SELECT DISTINCT ON (fr.game_id)\n"
                + "        fr.game_id, fr.period_end, fr.period_start, fr.date_precision,\n"
                + "        gs.slug, gs.canonical_title, gs.cover_reference, gs.cover_source,\n"
                + "        gs.cover_usage_mode, gs.cover_alternative_text, gs.cover_source_url\n"
                + "    FROM presented_release fr\n"
                + GAME_SNAPSHOT_LATERAL
                + "    ORDER BY fr.game_id, "
                + releaseOrder("fr")
                + "\n),\n"
                + "game_signal AS (\n"
                + signalSql(sort)
                + "),\n"
                + "game_page AS (\n"
                + "    SELECT * FROM game_signal gt\n"
                + "    ORDER BY "
                + gameOrder(sort, "gt")
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
                + "       rel.release_quarter, rel.release_stage, rel.release_status, rel.source_kind, rel.source_name,\n"
                + "       rel.source_entity_type, rel.provider_updated_at,\n"
                + "       rel.last_verified_at, rel.verification_level, rel.review_status\n"
                + "FROM game_page gp\n"
                + "JOIN LATERAL (\n"
                + "    SELECT fr.release_id, fr.platform_id, fr.region_id,\n"
                + "           p.display_name AS platform_name, r.display_name AS region_name,\n"
                + "           fr.date_precision, fr.exact_date, fr.release_year, fr.release_month,\n"
                + "           fr.release_quarter, fr.release_stage, fr.release_status, fr.source_kind, fr.source_name,\n"
                + "           fr.source_entity_type, fr.provider_updated_at,\n"
                + "           fr.last_verified_at, fr.verification_level, fr.review_status,\n"
                + "           fr.period_end, fr.period_start\n"
                + "    FROM presented_release fr\n"
                + "    JOIN catalogue.platform p ON p.platform_id = fr.platform_id\n"
                + "    JOIN catalogue.region r ON r.region_id = fr.region_id\n"
                + "    WHERE fr.game_id = gp.game_id\n"
                + "    ORDER BY "
                + releaseOrder("fr")
                + "\n    LIMIT :releaseGroupLimit\n"
                + ") rel ON true\n"
                + "ORDER BY "
                + gameOrder(sort, "gp")
                + ", "
                + releaseOrder("rel");
    }

    /** Each game's first presented release with the one signal the chosen order needs. */
    private static String signalSql(Sort sort) {
        return switch (sort) {
            case RELEASE_DATE -> "    SELECT gt.* FROM game_top gt\n";
            case HYPE ->
                    "    SELECT gt.*, fe.hypes FROM game_top gt\n"
                            + "    LEFT JOIN catalogue.game_featured_evidence fe"
                            + " ON fe.game_id = gt.game_id\n";
            case RATING ->
                    "    SELECT gt.*, cs.mean AS community_mean,"
                            + " cs.rating_count AS community_rating_count FROM game_top gt\n"
                            + "    LEFT JOIN catalogue.game_community_score cs"
                            + " ON cs.game_id = gt.game_id\n";
            // The /10 value at the one decimal discovery shows, as the external score mapping
            // rounds it; a check that found no score has no value and sorts with the unscored.
            case EXTERNAL_PLAYER_SCORE ->
                    "    SELECT gt.*, round(es.score / 10, 1) AS player_score,"
                            + " es.score_count AS player_score_count FROM game_top gt\n"
                            + "    LEFT JOIN catalogue.game_external_score es"
                            + " ON es.game_id = gt.game_id AND es.source_name = 'IGDB'"
                            + " AND es.score_kind = 'player' AND es.score IS NOT NULL\n";
        };
    }

    private record RangePredicate(String predicate, Map<String, Object> parameters) {}

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

        private Query build(Sort sort) {
            if (predicates.isEmpty()) {
                throw new IllegalStateException("Release browse query is incomplete");
            }
            String where = " WHERE " + String.join(" AND ", predicates);
            Sql sql = new Sql(COUNT_SELECT + where, pageSql(sort, where));
            return new Query(sql, Map.copyOf(parameters));
        }
    }
}
