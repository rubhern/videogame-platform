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
    private static final String PUBLICATION_PREDICATE = ReleaseRangeSql.PUBLICATION_PREDICATE;
    private static final String PLATFORM_ID_COLUMN = ReleaseRangeSql.PLATFORM_ID_COLUMN;
    private static final String REGION_ID_COLUMN = ReleaseRangeSql.REGION_ID_COLUMN;

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
        List<String> validPlatformIds =
                ReleaseRangeSql.existing(
                        jdbcOperations, requestedPlatformIds, "platform", "platform_id");
        List<String> validRegionIds =
                ReleaseRangeSql.existing(jdbcOperations, requestedRegionIds, "region", "region_id");

        ReleaseRangeSql.RangePredicate range = rangePredicate(criteria);
        // Faceted availability: each dimension reflects the current range and the other
        // dimension's active selection, is never narrowed by its own selection, and always keeps a
        // selected
        // valid value representable.
        List<Taxonomy> platforms =
                ReleaseRangeSql.facet(
                        jdbcOperations,
                        "platform",
                        PLATFORM_ID_COLUMN,
                        REGION_ID_COLUMN,
                        publication.id(),
                        range,
                        validRegionIds,
                        validPlatformIds);
        List<Taxonomy> regions =
                ReleaseRangeSql.facet(
                        jdbcOperations,
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

    private static ReleaseRangeSql.RangePredicate rangePredicate(Criteria criteria) {
        return ReleaseRangeSql.range(
                criteria.window().from(),
                criteria.window().to(),
                criteria.evaluatedOn(),
                criteria.includeApproximateDates());
    }

    private static Query query(
            String publicationId,
            Criteria criteria,
            ReleaseRangeSql.RangePredicate range,
            List<String> platformIds,
            List<String> regionIds) {
        CandidateQueryBuilder builder =
                new CandidateQueryBuilder()
                        .where(PUBLICATION_PREDICATE)
                        .bind("publicationId", publicationId)
                        .where(range.predicate());
        range.parameters().forEach(builder::bind);
        if (!platformIds.isEmpty()) {
            builder.where(ReleaseRangeSql.PLATFORM_PREDICATE)
                    .bind("platformIds", ReleaseRangeSql.arrayLiteral(platformIds));
        }
        if (!regionIds.isEmpty()) {
            builder.where(ReleaseRangeSql.REGION_PREDICATE)
                    .bind("regionIds", ReleaseRangeSql.arrayLiteral(regionIds));
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
