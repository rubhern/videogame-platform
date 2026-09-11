package com.videogameplatform.ratings.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.RatingAlreadyExistsException;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingNotFoundException;
import com.videogameplatform.ratings.application.RatingWriteConflictException;
import com.videogameplatform.ratings.application.RatingWriteException;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.domain.RatingValue;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "management.server.port=0")
@Execution(ExecutionMode.SAME_THREAD)
class JdbcPersonalRatingStoreIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("personal_rating_store");
    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

    @Autowired PersonalRatingStore store;
    private JdbcTemplate admin;
    private String game;
    private String user;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, true);
    }

    @BeforeEach
    void resetRatings() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.execute("DROP TRIGGER IF EXISTS reject_rating_update ON ratings.rating");
        admin.execute("DROP FUNCTION IF EXISTS ratings.reject_rating_update()");
        admin.update("DELETE FROM ratings.rating");
        game = UUID.randomUUID().toString();
        user = UUID.randomUUID().toString();
        admin.update(
                "INSERT INTO catalogue.game(game_id, created_at) VALUES (?, now())",
                UUID.fromString(game));
    }

    @Test
    void createsReadsUpdatesAndDeletesWithCoherentDatabaseAggregates() {
        String otherUser = UUID.randomUUID().toString();
        store.create(otherUser, game, new RatingValue(7), NOW, UUID.randomUUID().toString());

        RatingCommandResult created =
                store.create(user, game, new RatingValue(9), NOW, UUID.randomUUID().toString());
        assertThat(created.personalRating().value()).isEqualTo(9);
        assertThat(created.statistics().count()).isEqualTo(2);
        assertThat(created.statistics().mean()).isEqualByComparingTo("8.0");
        assertThat(created.statistics().distribution().get(8)).isEqualTo(1);
        assertThat(store.find(user, game)).contains(created.personalRating());

        Instant later = NOW.plusSeconds(60);
        RatingCommandResult updated =
                store.update(
                        user,
                        game,
                        new RatingValue(5),
                        later,
                        created.personalRating().versionToken(),
                        UUID.randomUUID().toString());
        assertThat(updated.personalRating().createdAt()).isEqualTo(NOW);
        assertThat(updated.personalRating().updatedAt()).isEqualTo(later);
        assertThat(updated.personalRating().versionToken())
                .isNotEqualTo(created.personalRating().versionToken());
        assertThat(updated.statistics().mean()).isEqualByComparingTo("6.0");

        var afterDelete = store.delete(user, game, updated.personalRating().versionToken());
        assertThat(afterDelete.count()).isEqualTo(1);
        assertThat(afterDelete.mean()).isEqualByComparingTo("7.0");
        assertThat(store.find(user, game)).isEmpty();
    }

    @Test
    void databaseDefaultsBackfillCommandMetadataAndConstraintsRemainDurable() {
        admin.update(
                "INSERT INTO ratings.rating(user_id, game_id, value) VALUES (?, ?, 6)",
                UUID.fromString(user),
                UUID.fromString(game));

        PersonalRating rating = store.find(user, game).orElseThrow();
        assertThat(rating.createdAt()).isNotNull();
        assertThat(rating.updatedAt()).isEqualTo(rating.createdAt());
        assertThat(UUID.fromString(rating.versionToken())).isNotNull();
        assertThatThrownBy(
                        () ->
                                admin.update(
                                        "UPDATE ratings.rating SET updated_at = created_at - interval '1 second' WHERE user_id = ? AND game_id = ?",
                                        UUID.fromString(user),
                                        UUID.fromString(game)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentCreatesHaveOneWinnerAndOneAlreadyExistsOutcome() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> create =
                () -> {
                    start.await();
                    try {
                        return store.create(
                                user, game, new RatingValue(8), NOW, UUID.randomUUID().toString());
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                };

        List<Object> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            outcomes = List.of(first.get(), second.get());
        }

        assertThat(outcomes).filteredOn(RatingCommandResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(RatingAlreadyExistsException.class::isInstance).hasSize(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM ratings.rating", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentUpdatesWithOneVersionNeverOverwriteTheWinner() throws Exception {
        PersonalRating initial =
                store.create(user, game, new RatingValue(4), NOW, UUID.randomUUID().toString())
                        .personalRating();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> updateToFive = update(start, initial.versionToken(), 5);
        Callable<Object> updateToNine = update(start, initial.versionToken(), 9);

        List<Object> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(updateToFive);
            var second = executor.submit(updateToNine);
            start.countDown();
            outcomes = List.of(first.get(), second.get());
        }

        assertThat(outcomes).filteredOn(RatingCommandResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(RatingWriteConflictException.class::isInstance).hasSize(1);
        RatingCommandResult winner =
                outcomes.stream()
                        .filter(RatingCommandResult.class::isInstance)
                        .map(RatingCommandResult.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(store.find(user, game)).contains(winner.personalRating());
        assertThat(winner.personalRating().value()).isIn(5, 9);
    }

    @Test
    void ownershipScopeLooksAbsentAndCannotMutateAnotherUsersRating() {
        PersonalRating ownerRating =
                store.create(user, game, new RatingValue(8), NOW, UUID.randomUUID().toString())
                        .personalRating();
        String otherUser = UUID.randomUUID().toString();

        assertThat(store.find(otherUser, game)).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.update(
                                        otherUser,
                                        game,
                                        new RatingValue(1),
                                        NOW.plusSeconds(1),
                                        ownerRating.versionToken(),
                                        UUID.randomUUID().toString()))
                .isInstanceOf(RatingNotFoundException.class);
        assertThatThrownBy(() -> store.delete(otherUser, game, ownerRating.versionToken()))
                .isInstanceOf(RatingNotFoundException.class);
        assertThat(store.find(user, game)).contains(ownerRating);
    }

    @Test
    void failureAfterTheUpdateStatementRollsBackPersonalAndAggregateState() {
        PersonalRating before =
                store.create(user, game, new RatingValue(6), NOW, UUID.randomUUID().toString())
                        .personalRating();
        admin.execute(
                """
                CREATE FUNCTION ratings.reject_rating_update() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced transactional failure';
                END
                $$
                """);
        admin.execute(
                """
                CREATE TRIGGER reject_rating_update AFTER UPDATE ON ratings.rating
                FOR EACH ROW EXECUTE FUNCTION ratings.reject_rating_update()
                """);

        assertThatThrownBy(
                        () ->
                                store.update(
                                        user,
                                        game,
                                        new RatingValue(10),
                                        NOW.plusSeconds(1),
                                        before.versionToken(),
                                        UUID.randomUUID().toString()))
                .isInstanceOf(RatingWriteException.class);

        assertThat(store.find(user, game)).contains(before);
        var statistics = new JdbcRatingStatisticsReadAdapter(namedRuntimeJdbc()).read(game);
        assertThat(statistics)
                .isInstanceOfSatisfying(
                        com.videogameplatform.ratings.application.RatingStatistics.Available.class,
                        available -> {
                            assertThat(available.count()).isEqualTo(1);
                            assertThat(available.mean()).isEqualByComparingTo("6.0");
                        });
    }

    private Callable<Object> update(CountDownLatch start, String version, int value) {
        return () -> {
            start.await();
            try {
                return store.update(
                        user,
                        game,
                        new RatingValue(value),
                        NOW.plusSeconds(value),
                        version,
                        UUID.randomUUID().toString());
            } catch (RuntimeException exception) {
                return exception;
            }
        };
    }

    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedRuntimeJdbc() {
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword()));
    }
}
