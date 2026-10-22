package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.PersonalRatingInsights;
import com.videogameplatform.ratings.application.PersonalRatingInsights.Extreme;
import com.videogameplatform.ratings.application.PersonalRatingsReadException;
import com.videogameplatform.ratings.application.port.PersonalRatingInsightsReadPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * All aggregation stays in PostgreSQL over one contribution per rating and the user's recorded
 * platforms; at most 43 result rows cross into Java.
 */
public final class JdbcPersonalRatingInsightsReadAdapter implements PersonalRatingInsightsReadPort {
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcPersonalRatingInsightsReadAdapter(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public PersonalRatingInsights read(String userId, LocalDate evaluatedOn) {
        try {
            return Objects.requireNonNull(
                    transaction.execute(status -> query(userId, evaluatedOn)));
        } catch (DataAccessException | TransactionException | IllegalArgumentException failure) {
            throw new PersonalRatingsReadException(failure);
        }
    }

    private PersonalRatingInsights query(String userId, LocalDate date) {
        var last = YearMonth.from(date);
        var first = last.minusMonths(11);
        var zone = ZoneId.of("Europe/Madrid");
        Map<String, Object> params = new HashMap<>();
        params.put("user", UUID.fromString(userId));
        params.put("start", first.atDay(1).atStartOfDay(zone).toOffsetDateTime());
        params.put("end", last.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime());
        params.put("firstMonth", first.atDay(1));
        params.put("lastMonth", last.atDay(1));
        var distribution =
                Objects.requireNonNull(
                        jdbc.query(
                                PersonalRatingInsightsSql.DISTRIBUTION,
                                params,
                                rs -> {
                                    var counts = new ArrayList<Long>(10);
                                    long total = 0;
                                    BigDecimal mean = null;
                                    while (rs.next()) {
                                        counts.add(rs.getLong("count"));
                                        total = rs.getLong("total");
                                        mean = rs.getBigDecimal("mean");
                                    }
                                    return new Distribution(total, mean, counts);
                                }));
        var coverage =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                PersonalRatingInsightsSql.COVERAGE,
                                params,
                                (rs, row) ->
                                        new long[] {
                                            rs.getLong("missing_genres"),
                                            rs.getLong("older"),
                                            rs.getLong("missing_platforms"),
                                            rs.getLong("several_platforms")
                                        }));
        var activity =
                jdbc.query(
                        PersonalRatingInsightsSql.ACTIVITY,
                        params,
                        (rs, row) ->
                                new PersonalRatingInsights.Month(
                                        YearMonth.parse(rs.getString("month")),
                                        rs.getLong("count")));
        return new PersonalRatingInsights(
                date,
                distribution.total(),
                distribution.mean(),
                distribution.counts(),
                genres(params, false),
                genres(params, true),
                coverage[0],
                extreme(params, true),
                extreme(params, false),
                activity,
                coverage[1],
                platforms(params, coverage[2], coverage[3]));
    }

    private PersonalRatingInsights.Platforms platforms(
            Map<String, Object> params, long withoutPlatforms, long onSeveralPlatforms) {
        var rows =
                jdbc.query(
                        PersonalRatingInsightsSql.PLATFORMS,
                        params,
                        (rs, row) ->
                                new PlatformRow(
                                        new PersonalRatingInsights.Platform(
                                                rs.getString("platform_id"),
                                                rs.getString("display_name"),
                                                rs.getLong("played"),
                                                rs.getLong("scored"),
                                                rs.getBigDecimal("mean")),
                                        rs.getLong("platforms")));
        long recorded = rows.isEmpty() ? 0 : rows.getFirst().recorded();
        return new PersonalRatingInsights.Platforms(
                rows.stream().map(PlatformRow::platform).toList(),
                recorded - rows.size(),
                withoutPlatforms,
                onSeveralPlatforms);
    }

    private List<PersonalRatingInsights.Genre> genres(
            Map<String, Object> params, boolean bestRated) {
        return jdbc.query(
                PersonalRatingInsightsSql.genres(bestRated),
                params,
                (rs, row) ->
                        new PersonalRatingInsights.Genre(
                                rs.getString("genre_code"),
                                rs.getString("display_name"),
                                rs.getLong("count"),
                                rs.getBigDecimal("mean")));
    }

    private Extreme extreme(Map<String, Object> params, boolean highest) {
        return jdbc
                .query(
                        PersonalRatingInsightsSql.extreme(highest),
                        params,
                        (rs, row) ->
                                new Extreme(
                                        rs.getString("game_id"),
                                        rs.getString("slug"),
                                        rs.getString("canonical_title"),
                                        JdbcPersonalRatingsReadAdapter.cover(rs),
                                        rs.getBigDecimal("value"),
                                        rs.getLong("tied")))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private record Distribution(long total, BigDecimal mean, List<Long> counts) {}

    private record PlatformRow(PersonalRatingInsights.Platform platform, long recorded) {}
}
