package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.PersonalRatingReadException;
import com.videogameplatform.ratings.application.RatingAlreadyExistsException;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingNotFoundException;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.RatingWriteConflictException;
import com.videogameplatform.ratings.application.RatingWriteException;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.domain.RatingValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL current-state store with conditional single-row writes and atomic aggregate reads. */
public final class JdbcPersonalRatingStore implements PersonalRatingStore {
    private static final String RETURNING =
            " RETURNING game_id, value, created_at, updated_at, version_token";

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcPersonalRatingStore(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Optional<PersonalRating> find(String userId, String gameId) {
        Optional<UUID> user = uuid(userId);
        Optional<UUID> game = uuid(gameId);
        if (user.isEmpty() || game.isEmpty()) {
            return Optional.empty();
        }
        try {
            return find(user.get(), game.get());
        } catch (DataAccessException | ArithmeticException exception) {
            throw new PersonalRatingReadException(exception);
        }
    }

    @Override
    public RatingCommandResult create(
            String userId, String gameId, RatingValue value, Instant now, String versionToken) {
        return write(
                () -> {
                    MapSqlParameterSource parameters =
                            parameters(userId, gameId)
                                    .addValue("value", value.value())
                                    .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                                    .addValue("version", UUID.fromString(versionToken));
                    List<PersonalRating> inserted =
                            jdbc.query(
                                    """
                                    INSERT INTO ratings.rating(
                                        user_id, game_id, value, created_at, updated_at, version_token)
                                    VALUES (:user, :game, :value, :now, :now, :version)
                                    ON CONFLICT (user_id, game_id) DO NOTHING
                                    """
                                            + RETURNING,
                                    parameters,
                                    JdbcPersonalRatingStore::rating);
                    if (inserted.isEmpty()) {
                        throw new RatingAlreadyExistsException();
                    }
                    return result(inserted.getFirst(), gameId);
                });
    }

    @Override
    public RatingCommandResult update(
            String userId,
            String gameId,
            RatingValue value,
            Instant now,
            String expectedVersion,
            String nextVersion) {
        return write(
                () -> {
                    UUID user = UUID.fromString(userId);
                    UUID game = UUID.fromString(gameId);
                    requireCurrentVersion(user, game, expectedVersion);
                    MapSqlParameterSource parameters =
                            parameters(userId, gameId)
                                    .addValue("value", value.value())
                                    .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                                    .addValue("expected", UUID.fromString(expectedVersion))
                                    .addValue("next", UUID.fromString(nextVersion));
                    List<PersonalRating> updated =
                            jdbc.query(
                                    """
                                    UPDATE ratings.rating
                                    SET value = :value, updated_at = :now, version_token = :next
                                    WHERE user_id = :user AND game_id = :game
                                      AND version_token = :expected
                                    """
                                            + RETURNING,
                                    parameters,
                                    JdbcPersonalRatingStore::rating);
                    if (updated.isEmpty()) {
                        throw new RatingWriteConflictException();
                    }
                    return result(updated.getFirst(), gameId);
                });
    }

    @Override
    public RatingStatistics.Available delete(String userId, String gameId, String expectedVersion) {
        return write(
                () -> {
                    UUID user = UUID.fromString(userId);
                    UUID game = UUID.fromString(gameId);
                    requireCurrentVersion(user, game, expectedVersion);
                    int deleted =
                            jdbc.update(
                                    """
                                    DELETE FROM ratings.rating
                                    WHERE user_id = :user AND game_id = :game
                                      AND version_token = :expected
                                    """,
                                    parameters(userId, gameId)
                                            .addValue(
                                                    "expected", UUID.fromString(expectedVersion)));
                    if (deleted == 0) {
                        throw new RatingWriteConflictException();
                    }
                    return RatingStatisticsQuery.read(jdbc, gameId);
                });
    }

    private RatingCommandResult result(PersonalRating rating, String gameId) {
        return new RatingCommandResult(rating, RatingStatisticsQuery.read(jdbc, gameId));
    }

    private void requireCurrentVersion(UUID userId, UUID gameId, String expectedVersion) {
        Optional<PersonalRating> current = find(userId, gameId);
        if (current.isEmpty()) {
            throw new RatingNotFoundException();
        }
        if (!current.get().versionToken().equals(expectedVersion)) {
            throw new RatingWriteConflictException();
        }
    }

    private Optional<PersonalRating> find(UUID userId, UUID gameId) {
        return jdbc
                .query(
                        """
                        SELECT game_id, value, created_at, updated_at, version_token
                        FROM ratings.rating
                        WHERE user_id = :user AND game_id = :game
                        """,
                        Map.of("user", userId, "game", gameId),
                        JdbcPersonalRatingStore::rating)
                .stream()
                .findFirst();
    }

    private static PersonalRating rating(ResultSet result, int row) throws SQLException {
        return new PersonalRating(
                result.getString("game_id"),
                result.getInt("value"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                result.getString("version_token"));
    }

    private static MapSqlParameterSource parameters(String userId, String gameId) {
        return new MapSqlParameterSource()
                .addValue("user", UUID.fromString(userId))
                .addValue("game", UUID.fromString(gameId));
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private <T> T write(Command<T> command) {
        try {
            T result = transaction.execute(status -> command.execute());
            if (result == null) {
                throw new IllegalStateException("Rating transaction returned no result");
            }
            return result;
        } catch (RatingAlreadyExistsException
                | RatingNotFoundException
                | RatingWriteConflictException exception) {
            throw exception;
        } catch (DataAccessException
                | TransactionException
                | IllegalArgumentException
                | ArithmeticException exception) {
            throw new RatingWriteException(exception);
        }
    }

    @FunctionalInterface
    private interface Command<T> {
        T execute();
    }
}
