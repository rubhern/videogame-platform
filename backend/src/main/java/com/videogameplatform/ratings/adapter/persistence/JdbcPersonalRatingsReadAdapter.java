package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.PersonalRatingsPage;
import com.videogameplatform.ratings.application.PersonalRatingsReadException;
import com.videogameplatform.ratings.application.port.PersonalRatingsReadPort;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
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
        var items =
                jdbc.query(
                        PersonalRatingsSql.page(criteria),
                        parameters,
                        JdbcPersonalRatingsReadAdapter::item);
        return new PersonalRatingsPage(items, criteria.page(), criteria.pageSize(), total);
    }

    private static PersonalRatingsPage.Item item(ResultSet rs, int row) throws SQLException {
        CatalogueCover cover =
                switch (rs.getString("cover_kind")) {
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
        var rating =
                new PersonalRating(
                        rs.getString("game_id"),
                        rs.getInt("value"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("version_token"));
        return new PersonalRatingsPage.Item(
                rating.gameId(),
                rs.getString("slug"),
                rs.getString("canonical_title"),
                cover,
                rating);
    }
}
