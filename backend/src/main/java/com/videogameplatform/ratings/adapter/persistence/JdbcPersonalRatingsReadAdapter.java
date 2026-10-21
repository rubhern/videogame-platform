package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.PersonalRatingsPage;
import com.videogameplatform.ratings.application.PersonalRatingsReadException;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.port.PersonalRatingsReadPort;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/** One database snapshot for the user-scoped count and bounded page. */
public final class JdbcPersonalRatingsReadAdapter implements PersonalRatingsReadPort {
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcPersonalRatingsReadAdapter(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public PersonalRatingsPage read(String userId, Criteria criteria) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> query(userId, criteria)));
        } catch (DataAccessException | TransactionException | IllegalArgumentException failure) {
            throw new PersonalRatingsReadException(failure);
        }
    }

    private PersonalRatingsPage query(String userId, Criteria criteria) {
        var parameters = PersonalRatingsSql.parameters(userId, criteria);
        long total =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                PersonalRatingsSql.count(criteria), parameters, Long.class));
        var rows =
                jdbc.query(
                        PersonalRatingsSql.page(criteria),
                        parameters,
                        JdbcPersonalRatingsReadAdapter::row);
        List<PersonalRatingsPage.Item> items = List.of();
        if (!rows.isEmpty()) {
            var ids = rows.stream().map(row -> UUID.fromString(row.gameId())).toList();
            // The page's played platforms, read in the same snapshot as its ratings.
            var platforms =
                    RatingPlatformRows.forGames(
                            jdbc, userId, rows.stream().map(PageRow::gameId).toList());
            items =
                    rows.stream()
                            .map(row -> row.item(platforms.getOrDefault(row.gameId(), List.of())))
                            .toList();
            var metadataParams = Map.<String, Object>of("gameIds", ids);
            Map<String, List<GameDetailsResult.Term>> genres = new HashMap<>();
            jdbc.query(
                    PersonalRatingsSql.GENRES,
                    metadataParams,
                    (RowCallbackHandler)
                            rs ->
                                    genres.computeIfAbsent(
                                                    rs.getString("game_id"),
                                                    ignored -> new ArrayList<>())
                                            .add(
                                                    new GameDetailsResult.Term(
                                                            rs.getString("genre_code"),
                                                            rs.getString("display_name"))));
            Map<String, RatingStatistics.Summary> summaries = new HashMap<>();
            // An isolated statistics failure must not abort the coherent personal read transaction.
            jdbc.getJdbcOperations().execute("SAVEPOINT rating_collection_statistics");
            try {
                jdbc.query(
                        PersonalRatingsSql.SUMMARIES,
                        Map.of(
                                "gameIds",
                                "{"
                                        + ids.stream()
                                                .map(Object::toString)
                                                .collect(Collectors.joining(","))
                                        + "}"),
                        (RowCallbackHandler)
                                rs ->
                                        summaries.put(
                                                rs.getString("game_id"),
                                                new RatingStatistics.Summary(
                                                        rs.getBigDecimal("mean"),
                                                        Math.toIntExact(rs.getLong("count")))));
            } catch (DataAccessException unavailable) {
                jdbc.getJdbcOperations()
                        .execute("ROLLBACK TO SAVEPOINT rating_collection_statistics");
                summaries.clear();
            } finally {
                jdbc.getJdbcOperations().execute("RELEASE SAVEPOINT rating_collection_statistics");
            }
            items =
                    items.stream()
                            .map(
                                    item ->
                                            new PersonalRatingsPage.Item(
                                                    item.gameId(),
                                                    item.slug(),
                                                    item.canonicalTitle(),
                                                    item.cover(),
                                                    item.rating(),
                                                    genres.getOrDefault(item.gameId(), List.of()),
                                                    Optional.ofNullable(
                                                            summaries.get(item.gameId()))))
                            .toList();
        }
        return new PersonalRatingsPage(items, criteria.page(), criteria.pageSize(), total);
    }

    private static PageRow row(ResultSet rs, int row) throws SQLException {
        return new PageRow(
                rs.getString("game_id"),
                rs.getString("slug"),
                rs.getString("canonical_title"),
                cover(rs),
                rs.getObject("value") == null ? null : rs.getInt("value"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("version_token"));
    }

    /** One paged rating before its played platforms are attached. */
    private record PageRow(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCover cover,
            Integer value,
            Instant createdAt,
            Instant updatedAt,
            String versionToken) {
        PersonalRatingsPage.Item item(List<PersonalRating.PlayedPlatform> platforms) {
            var rating =
                    new PersonalRating(
                            gameId, value, platforms, createdAt, updatedAt, versionToken);
            return new PersonalRatingsPage.Item(
                    gameId, slug, canonicalTitle, cover, rating, List.of(), Optional.empty());
        }
    }

    static CatalogueCover cover(ResultSet rs) throws SQLException {
        return switch (rs.getString("cover_kind")) {
            case "provider" ->
                    new CatalogueCover.Provider(
                            URI.create(rs.getString("cover_reference")),
                            rs.getString("cover_alternative_text"),
                            new CatalogueCover.Attribution(
                                    rs.getString("cover_attribution"),
                                    URI.create(rs.getString("cover_source_url"))));
            case "product" ->
                    new CatalogueCover.Product(
                            rs.getString("cover_reference"),
                            rs.getString("cover_alternative_text"));
            default -> new CatalogueCover.Unavailable();
        };
    }
}
