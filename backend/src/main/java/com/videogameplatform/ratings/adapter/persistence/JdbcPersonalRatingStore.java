package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase;
import com.videogameplatform.ratings.adapter.observability.RatingActivityMetrics;
import com.videogameplatform.ratings.adapter.observability.RatingActivityMetrics.Action;
import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.PersonalRatingReadException;
import com.videogameplatform.ratings.application.RatingAlreadyExistsException;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingNotFoundException;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.RatingWriteConflictException;
import com.videogameplatform.ratings.application.RatingWriteException;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.domain.PlatformScore;
import com.videogameplatform.ratings.domain.RatingScore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL current-state store with conditional single-row writes and atomic aggregate reads. A
 * write stores the rating row, its whole platform set and the platforms' labels together, so the
 * row's platform score summary, from which PostgreSQL derives the rating's one contribution,
 * always describes the platform rows committed with it. Every write also replaces the game's
 * community score in Catalogue's projection (ADR-0029) in the same transaction, serialized per
 * game so concurrent writes never project a stale aggregate.
 */
public final class JdbcPersonalRatingStore implements PersonalRatingStore {
    private static final String INSERT_RATING =
            """
            INSERT INTO ratings.rating(
                user_id, game_id, value, platform_score_count, platform_score_sum,
                created_at, updated_at, version_token)
            VALUES (:user, :game, :value, :scored, :sum, :now, :now, :version)
            ON CONFLICT (user_id, game_id) DO NOTHING
            """;
    private static final String UPDATE_RATING =
            """
            UPDATE ratings.rating
            SET value = :value, platform_score_count = :scored, platform_score_sum = :sum,
                updated_at = :now, version_token = :next
            WHERE user_id = :user AND game_id = :game
              AND version_token = :expected
            """;
    private static final String FIND_RATING =
            "SELECT r.game_id, r.value, r.created_at, r.updated_at, r.version_token,"
                    + " platforms.* FROM ratings.rating r CROSS JOIN LATERAL ("
                    + RatingPlatformRows.AGGREGATED
                    + ") platforms WHERE r.user_id = :user AND r.game_id = :game";
    // Labels are taken in platform order, so concurrent writers never wait on each other's
    // label rows in opposite orders.
    private static final String UPSERT_LABEL =
            """
            INSERT INTO ratings.platform_label(platform_id, display_name)
            VALUES (:platform, :name)
            ON CONFLICT (platform_id) DO UPDATE SET display_name = EXCLUDED.display_name
            WHERE ratings.platform_label.display_name <> EXCLUDED.display_name
            """;
    private static final String INSERT_PLATFORM =
            """
            INSERT INTO ratings.rating_platform(user_id, game_id, platform_id, value)
            VALUES (:user, :game, :platform, :value)
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final RatingActivityMetrics activity;
    private final RecordCommunityScoresUseCase scores;

    public JdbcPersonalRatingStore(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations transaction,
            RatingActivityMetrics activity,
            RecordCommunityScoresUseCase scores) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.activity = activity;
        this.scores = scores;
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
        } catch (DataAccessException | ArithmeticException | IllegalArgumentException exception) {
            throw new PersonalRatingReadException(exception);
        }
    }

    @Override
    public RatingCommandResult create(
            String userId,
            String gameId,
            RatingScore score,
            Map<String, String> labels,
            Instant now,
            String versionToken) {
        return write(
                Action.CREATED,
                gameId,
                () -> {
                    recordLabels(labels);
                    int inserted =
                            jdbc.update(
                                    INSERT_RATING,
                                    score(parameters(userId, gameId), score)
                                            .addValue("now", timestamp(now))
                                            .addValue("version", UUID.fromString(versionToken)));
                    if (inserted == 0) {
                        throw new RatingAlreadyExistsException();
                    }
                    recordPlatforms(userId, gameId, score);
                    return result(userId, gameId);
                });
    }

    @Override
    public RatingCommandResult update(
            String userId,
            String gameId,
            RatingScore score,
            Map<String, String> labels,
            Instant now,
            String expectedVersion,
            String nextVersion) {
        return write(
                Action.UPDATED,
                gameId,
                () -> {
                    UUID user = UUID.fromString(userId);
                    UUID game = UUID.fromString(gameId);
                    requireCurrentVersion(user, game, expectedVersion);
                    recordLabels(labels);
                    int updated =
                            jdbc.update(
                                    UPDATE_RATING,
                                    score(parameters(userId, gameId), score)
                                            .addValue("now", timestamp(now))
                                            .addValue("expected", UUID.fromString(expectedVersion))
                                            .addValue("next", UUID.fromString(nextVersion)));
                    if (updated == 0) {
                        throw new RatingWriteConflictException();
                    }
                    jdbc.update(
                            "DELETE FROM ratings.rating_platform"
                                    + " WHERE user_id = :user AND game_id = :game",
                            parameters(userId, gameId));
                    recordPlatforms(userId, gameId, score);
                    return result(userId, gameId);
                });
    }

    @Override
    public RatingStatistics.Available delete(String userId, String gameId, String expectedVersion) {
        return write(
                Action.DELETED,
                gameId,
                () -> {
                    UUID user = UUID.fromString(userId);
                    UUID game = UUID.fromString(gameId);
                    requireCurrentVersion(user, game, expectedVersion);
                    // The rating's platforms cascade with it.
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
                    return project(gameId);
                });
    }

    private void recordLabels(Map<String, String> labels) {
        for (var label : new TreeMap<>(labels).entrySet()) {
            jdbc.update(
                    UPSERT_LABEL,
                    Map.of("platform", UUID.fromString(label.getKey()), "name", label.getValue()));
        }
    }

    private void recordPlatforms(String userId, String gameId, RatingScore score) {
        if (score.platforms().isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                INSERT_PLATFORM,
                score.platforms().stream()
                        .map(platform -> platform(userId, gameId, platform))
                        .toArray(SqlParameterSource[]::new));
    }

    private static SqlParameterSource platform(
            String userId, String gameId, PlatformScore platform) {
        return parameters(userId, gameId)
                .addValue("platform", UUID.fromString(platform.platformId()))
                .addValue(
                        "value",
                        platform.value() == null ? null : platform.value().value(),
                        java.sql.Types.SMALLINT);
    }

    private static MapSqlParameterSource score(
            MapSqlParameterSource parameters, RatingScore score) {
        return parameters
                .addValue(
                        "value",
                        score.direct() == null ? null : score.direct().value(),
                        java.sql.Types.SMALLINT)
                .addValue("scored", score.scoredPlatforms())
                .addValue("sum", score.platformScoreSum());
    }

    private RatingCommandResult result(String userId, String gameId) {
        PersonalRating rating =
                find(UUID.fromString(userId), UUID.fromString(gameId))
                        .orElseThrow(
                                () -> new IllegalStateException("A written rating was not found"));
        return new RatingCommandResult(rating, project(gameId));
    }

    /** Reads the game's aggregate after the write and projects it for the release order. */
    private RatingStatistics.Available project(String gameId) {
        RatingStatistics.Available statistics = RatingStatisticsQuery.read(jdbc, gameId);
        scores.replace(
                gameId,
                new RecordCommunityScoresUseCase.CommunityScore(
                        statistics.mean(), statistics.count()));
        return statistics;
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
                        FIND_RATING,
                        Map.of("user", userId, "game", gameId),
                        JdbcPersonalRatingStore::rating)
                .stream()
                .findFirst();
    }

    private static PersonalRating rating(ResultSet result, int row) throws SQLException {
        return new PersonalRating(
                result.getString("game_id"),
                result.getObject("value") == null ? null : result.getInt("value"),
                RatingPlatformRows.aggregated(result),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                result.getString("version_token"));
    }

    private static OffsetDateTime timestamp(Instant now) {
        return OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    private static MapSqlParameterSource parameters(String userId, String gameId) {
        return new MapSqlParameterSource()
                .addValue("user", UUID.fromString(userId))
                .addValue("game", UUID.fromString(gameId));
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException _) {
            return Optional.empty();
        }
    }

    private <T> T write(Action action, String gameId, Command<T> command) {
        try {
            return Objects.requireNonNull(
                    transaction.execute(
                            status -> {
                                CommunityScoreLock.acquire(jdbc, gameId);
                                T result = command.execute();
                                activity.afterCommit(action);
                                return result;
                            }),
                    "Rating transaction returned no result");
        } catch (RatingAlreadyExistsException
                | RatingNotFoundException
                | RatingWriteConflictException exception) {
            throw exception;
        } catch (DataAccessException
                | TransactionException
                | IllegalArgumentException
                | IllegalStateException
                | ArithmeticException exception) {
            throw new RatingWriteException(exception);
        }
    }

    @FunctionalInterface
    private interface Command<T> {
        T execute();
    }
}
