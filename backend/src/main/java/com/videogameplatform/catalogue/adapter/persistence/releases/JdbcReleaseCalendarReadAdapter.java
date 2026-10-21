package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL read adapter for the release calendar: per-day game counts of a month's remaining
 * days, classified by the release explorer's own range rules without approximate dates.
 * PostgreSQL filters, groups, counts and sums Hype once per game/day, so the application receives at most one row per day of
 * the month, never a release or a game.
 */
public final class JdbcReleaseCalendarReadAdapter implements ReleaseCalendarReadPort {

    // A game counts once per day however many of its releases fall on it, so a day's count is the
    // highlight-filtered explorer's game total for that single day without approximate dates.
    private static final String DAY_COUNTS =
            "SELECT day_game.exact_date, count(*) AS game_count, sum(fe.hypes) AS total_hype"
                    + " FROM (SELECT rs.exact_date, rs.game_id"
                    + " FROM catalogue.release_snapshot rs WHERE %s AND rs.date_precision = 'day'"
                    + " GROUP BY rs.exact_date, rs.game_id) day_game"
                    + " JOIN catalogue.game_featured_evidence fe ON fe.game_id = day_game.game_id"
                    + " GROUP BY day_game.exact_date ORDER BY day_game.exact_date";

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcReleaseCalendarReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> countPublishedReleases(Criteria criteria) {
        return CatalogueReadTransaction.execute(
                readTransaction, status -> countInTransaction(criteria));
    }

    private Optional<Result> countInTransaction(Criteria criteria) {
        Optional<CurrentPublicationReader.Publication> currentPublication =
                CurrentPublicationReader.read(jdbcOperations);
        if (currentPublication.isEmpty()) {
            return Optional.empty();
        }
        String publicationId = currentPublication.orElseThrow().id();

        List<String> validPlatformIds =
                ReleaseRangeSql.existing(
                        jdbcOperations, criteria.platformIds(), "platform", "platform_id");
        List<String> validRegionIds =
                ReleaseRangeSql.existing(
                        jdbcOperations, criteria.regionIds(), "region", "region_id");

        // Exact days only, as the explorer reads a single day without approximate dates; the range
        // starts no earlier than the evaluation date, so no ended period can overlap it.
        ReleaseRangeSql.RangePredicate range =
                ReleaseRangeSql.highlights(
                        ReleaseRangeSql.range(
                                criteria.from(), criteria.to(), criteria.evaluatedOn(), false));
        List<Taxonomy> platforms =
                ReleaseRangeSql.facet(
                        jdbcOperations,
                        "platform",
                        ReleaseRangeSql.PLATFORM_ID_COLUMN,
                        ReleaseRangeSql.REGION_ID_COLUMN,
                        publicationId,
                        range,
                        validRegionIds,
                        validPlatformIds);
        List<Taxonomy> regions =
                ReleaseRangeSql.facet(
                        jdbcOperations,
                        "region",
                        ReleaseRangeSql.REGION_ID_COLUMN,
                        ReleaseRangeSql.PLATFORM_ID_COLUMN,
                        publicationId,
                        range,
                        validPlatformIds,
                        validRegionIds);

        if (validPlatformIds.size() != criteria.platformIds().size()
                || validRegionIds.size() != criteria.regionIds().size()) {
            // The application rejects the unknown id by finding it absent from the facets.
            return Optional.of(new Result(platforms, regions, List.of()));
        }

        List<String> predicates = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>(range.parameters());
        predicates.add(ReleaseRangeSql.PUBLICATION_PREDICATE);
        parameters.put("publicationId", publicationId);
        predicates.add(range.predicate());
        if (!validPlatformIds.isEmpty()) {
            predicates.add(ReleaseRangeSql.PLATFORM_PREDICATE);
            parameters.put("platformIds", ReleaseRangeSql.arrayLiteral(validPlatformIds));
        }
        if (!validRegionIds.isEmpty()) {
            predicates.add(ReleaseRangeSql.REGION_PREDICATE);
            parameters.put("regionIds", ReleaseRangeSql.arrayLiteral(validRegionIds));
        }
        String where = String.join(" AND ", predicates);

        List<DayCount> days =
                jdbcOperations.query(
                        DAY_COUNTS.formatted(where),
                        parameters,
                        (resultSet, rowNumber) ->
                                new DayCount(
                                        resultSet.getObject("exact_date", LocalDate.class),
                                        resultSet.getLong("game_count"),
                                        resultSet.getLong("total_hype")));
        return Optional.of(new Result(platforms, regions, days));
    }
}
