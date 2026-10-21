package com.videogameplatform.ratings.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.RatingAlreadyExistsException;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingNotFoundException;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.RatingWriteConflictException;
import com.videogameplatform.ratings.application.RatingWriteException;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.domain.PlatformScore;
import com.videogameplatform.ratings.domain.RatingScore;
import com.videogameplatform.ratings.domain.RatingValue;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "management.server.port=0")
@Execution(ExecutionMode.SAME_THREAD)
class JdbcPersonalRatingStoreIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("personal_rating_store");
    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

    @Autowired PersonalRatingStore store;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager transactionManager;
    private JdbcTemplate admin;
    private String game;
    private String user;
    private String ps5;
    private String switchConsole;
    private String pc;

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
        admin.execute("DROP TRIGGER IF EXISTS reject_platform_insert ON ratings.rating_platform");
        admin.execute("DROP FUNCTION IF EXISTS ratings.reject_platform_insert()");
        admin.update("DELETE FROM ratings.rating");
        game = UUID.randomUUID().toString();
        user = UUID.randomUUID().toString();
        admin.update(
                "INSERT INTO catalogue.game(game_id, created_at) VALUES (?, now())",
                UUID.fromString(game));
        ps5 = platform();
        switchConsole = platform();
        pc = platform();
    }

    @Test
    void eachUserCastsOneVoteWhateverTheNumberOfScoredPlatforms() {
        RatingCommandResult platforms =
                store.create(
                        user,
                        game,
                        platforms(scored(ps5, 9), scored(switchConsole, 8), played(pc)),
                        labels(),
                        NOW,
                        UUID.randomUUID().toString());
        RatingCommandResult direct =
                store.create(
                        UUID.randomUUID().toString(),
                        game,
                        direct(9),
                        Map.of(),
                        NOW,
                        UUID.randomUUID().toString());

        PersonalRating rating = platforms.personalRating();
        assertThat(rating.mode()).isEqualTo(RatingScore.Mode.PLATFORMS);
        assertThat(rating.value()).isNull();
        assertThat(rating.contribution()).isEqualByComparingTo("8.5");
        assertThat(rating.platforms())
                .extracting(PersonalRating.PlayedPlatform::name)
                .containsExactly("Nintendo Switch", "PC", "PlayStation 5");
        assertThat(store.find(user, game)).contains(rating);

        RatingStatistics.Available statistics = direct.statistics();
        // Two voters: (8.5 + 9) / 2 = 8.75, shown half up; never (9 + 8 + 9) / 3.
        assertThat(statistics.count()).isEqualTo(2);
        assertThat(statistics.mean()).isEqualByComparingTo("8.8");
        assertThat(statistics.distribution().get(8)).isEqualTo(2);
        assertThat(statistics.distribution().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(2);
        assertThat(statistics.platforms())
                .containsExactly(
                        new RatingStatistics.PlatformStatistics(
                                switchConsole,
                                "Nintendo Switch",
                                new java.math.BigDecimal("8.0"),
                                1),
                        new RatingStatistics.PlatformStatistics(
                                ps5, "PlayStation 5", new java.math.BigDecimal("9.0"), 1));
        assertThat(
                        admin.queryForMap(
                                "SELECT mean, rating_count FROM catalogue.game_community_score WHERE game_id = ?",
                                UUID.fromString(game)))
                .containsEntry("rating_count", 2)
                .hasEntrySatisfying(
                        "mean",
                        mean ->
                                assertThat((java.math.BigDecimal) mean)
                                        .isEqualByComparingTo("8.8"));
    }

    @Test
    void anExactHalfWayMeanOfRepeatingContributionsRoundsUp() {
        // Three users at 25/3 and one at 10: the mean is exactly 8.75, though each 25/3 is stored
        // as a twenty-place quotient that sums just below it.
        for (int voter = 0; voter < 3; voter++) {
            store.create(
                    UUID.randomUUID().toString(),
                    game,
                    platforms(scored(ps5, 8), scored(switchConsole, 8), scored(pc, 9)),
                    labels(),
                    NOW,
                    UUID.randomUUID().toString());
        }
        var statistics =
                store.create(user, game, direct(10), Map.of(), NOW, UUID.randomUUID().toString())
                        .statistics();

        assertThat(statistics.count()).isEqualTo(4);
        assertThat(statistics.mean()).isEqualByComparingTo("8.8");
        assertThat(statistics.distribution().get(7)).isEqualTo(3);
        assertThat(statistics.distribution().get(9)).isEqualTo(1);
        assertThat(statistics.platforms())
                .extracting(RatingStatistics.PlatformStatistics::count)
                .containsExactly(3, 3, 3);
    }

    @Test
    void anUpdateReplacesThePlatformSetAndADeleteRemovesItWithTheRating() {
        PersonalRating created =
                store.create(
                                user,
                                game,
                                platforms(scored(ps5, 9), scored(switchConsole, 7)),
                                labels(),
                                NOW,
                                UUID.randomUUID().toString())
                        .personalRating();

        RatingCommandResult updated =
                store.update(
                        user,
                        game,
                        new RatingScore(new RatingValue(6), List.of(played(ps5))),
                        labels(),
                        NOW.plusSeconds(1),
                        created.versionToken(),
                        UUID.randomUUID().toString());

        assertThat(updated.personalRating().mode()).isEqualTo(RatingScore.Mode.DIRECT);
        assertThat(updated.personalRating().platforms())
                .containsExactly(new PersonalRating.PlayedPlatform(ps5, "PlayStation 5", null));
        assertThat(updated.statistics().mean()).isEqualByComparingTo("6.0");
        assertThat(updated.statistics().platforms()).isEmpty();

        store.delete(user, game, updated.personalRating().versionToken());
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM ratings.rating_platform WHERE user_id = ?",
                                Integer.class,
                                UUID.fromString(user)))
                .isZero();
    }

    @Test
    void aFailedPlatformWriteRollsBackTheWholeRating() {
        PersonalRating before =
                store.create(
                                user,
                                game,
                                platforms(scored(ps5, 9)),
                                labels(),
                                NOW,
                                UUID.randomUUID().toString())
                        .personalRating();
        admin.execute(
                """
                CREATE FUNCTION ratings.reject_platform_insert() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced platform failure';
                END
                $$
                """);
        admin.execute(
                """
                CREATE TRIGGER reject_platform_insert AFTER INSERT ON ratings.rating_platform
                FOR EACH ROW EXECUTE FUNCTION ratings.reject_platform_insert()
                """);
        RatingScore replacement = platforms(scored(ps5, 3), scored(pc, 4));
        Map<String, String> labels = labels();
        String version = before.versionToken();
        String next = UUID.randomUUID().toString();

        assertThatThrownBy(
                        () ->
                                store.update(
                                        user,
                                        game,
                                        replacement,
                                        labels,
                                        NOW.plusSeconds(1),
                                        version,
                                        next))
                .isInstanceOf(RatingWriteException.class);

        assertThat(store.find(user, game)).contains(before);
        assertThat(
                        admin.queryForObject(
                                "SELECT mean FROM catalogue.game_community_score WHERE game_id = ?",
                                java.math.BigDecimal.class,
                                UUID.fromString(game)))
                .isEqualByComparingTo("9.0");
    }

    @Test
    void theRatingRowAcceptsExactlyOneScoreSource() {
        store.create(
                user, game, platforms(scored(ps5, 9)), labels(), NOW, UUID.randomUUID().toString());
        UUID owner = UUID.fromString(user);
        UUID rated = UUID.fromString(game);

        assertThatThrownBy(
                        () ->
                                admin.update(
                                        "UPDATE ratings.rating SET value = 5 WHERE user_id = ? AND game_id = ?",
                                        owner,
                                        rated))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                admin.update(
                                        "UPDATE ratings.rating SET platform_score_count = 0, platform_score_sum = 0 WHERE user_id = ? AND game_id = ?",
                                        owner,
                                        rated))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                admin.update(
                                        "UPDATE ratings.rating_platform SET value = 11 WHERE user_id = ?",
                                        owner))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(
                        admin.queryForObject(
                                "SELECT contribution FROM ratings.rating WHERE user_id = ?",
                                java.math.BigDecimal.class,
                                owner))
                .isEqualByComparingTo("9");
    }

    @Test
    void aSavedPlatformKeepsItsLabelAndANewCatalogueLabelReplacesIt() {
        PersonalRating created =
                store.create(
                                user,
                                game,
                                platforms(scored(ps5, 9)),
                                labels(),
                                NOW,
                                UUID.randomUUID().toString())
                        .personalRating();

        // Without a current label the saved one stays; a current one replaces it for every rating.
        PersonalRating kept =
                store.update(
                                user,
                                game,
                                platforms(scored(ps5, 8)),
                                Map.of(),
                                NOW.plusSeconds(1),
                                created.versionToken(),
                                UUID.randomUUID().toString())
                        .personalRating();
        assertThat(kept.platforms())
                .extracting(PersonalRating.PlayedPlatform::name)
                .containsExactly("PlayStation 5");
        PersonalRating renamed =
                store.update(
                                user,
                                game,
                                platforms(scored(ps5, 8)),
                                Map.of(ps5, "PS5"),
                                NOW.plusSeconds(2),
                                kept.versionToken(),
                                UUID.randomUUID().toString())
                        .personalRating();
        assertThat(renamed.platforms())
                .extracting(PersonalRating.PlayedPlatform::name)
                .containsExactly("PS5");
    }

    @Test
    void createsReadsUpdatesAndDeletesWithCoherentDatabaseAggregates() {
        double createdBefore = activity("created");
        double updatedBefore = activity("updated");
        double deletedBefore = activity("deleted");
        String otherUser = UUID.randomUUID().toString();
        store.create(otherUser, game, direct(7), Map.of(), NOW, UUID.randomUUID().toString());

        RatingCommandResult created =
                store.create(user, game, direct(9), Map.of(), NOW, UUID.randomUUID().toString());
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
                        direct(5),
                        Map.of(),
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
        assertThat(activity("created") - createdBefore).isEqualTo(2);
        assertThat(activity("updated") - updatedBefore).isEqualTo(1);
        assertThat(activity("deleted") - deletedBefore).isEqualTo(1);
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
        double before = activity("created");
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> create =
                () -> {
                    start.await();
                    try {
                        return store.create(
                                user, game, direct(8), Map.of(), NOW, UUID.randomUUID().toString());
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
        assertThat(activity("created") - before).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM ratings.rating", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentUpdatesWithOneVersionNeverOverwriteTheWinner() throws Exception {
        double before = activity("updated");
        PersonalRating initial =
                store.create(user, game, direct(4), Map.of(), NOW, UUID.randomUUID().toString())
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
        assertThat(activity("updated") - before).isEqualTo(1);
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
        double updatedBefore = activity("updated");
        double deletedBefore = activity("deleted");
        PersonalRating ownerRating =
                store.create(user, game, direct(8), Map.of(), NOW, UUID.randomUUID().toString())
                        .personalRating();
        String otherUser = UUID.randomUUID().toString();
        RatingScore otherUsersRating = direct(1);
        Instant updateTime = NOW.plusSeconds(1);
        String ownerVersion = ownerRating.versionToken();
        String nextVersion = UUID.randomUUID().toString();

        assertThat(store.find(otherUser, game)).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.update(
                                        otherUser,
                                        game,
                                        otherUsersRating,
                                        Map.of(),
                                        updateTime,
                                        ownerVersion,
                                        nextVersion))
                .isInstanceOf(RatingNotFoundException.class);
        assertThatThrownBy(() -> store.delete(otherUser, game, ownerVersion))
                .isInstanceOf(RatingNotFoundException.class);
        assertThat(store.find(user, game)).contains(ownerRating);
        assertThat(activity("updated")).isEqualTo(updatedBefore);
        assertThat(activity("deleted")).isEqualTo(deletedBefore);
    }

    @Test
    void failureAfterTheUpdateStatementRollsBackPersonalAndAggregateState() {
        double beforeMetric = activity("updated");
        PersonalRating before =
                store.create(user, game, direct(6), Map.of(), NOW, UUID.randomUUID().toString())
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
        RatingScore replacementRating = direct(10);
        Instant updateTime = NOW.plusSeconds(1);
        String beforeVersion = before.versionToken();
        String nextVersion = UUID.randomUUID().toString();

        assertThatThrownBy(
                        () ->
                                store.update(
                                        user,
                                        game,
                                        replacementRating,
                                        Map.of(),
                                        updateTime,
                                        beforeVersion,
                                        nextVersion))
                .isInstanceOf(RatingWriteException.class);

        assertThat(store.find(user, game)).contains(before);
        assertThat(activity("updated")).isEqualTo(beforeMetric);
        var statistics = runtimeStatistics().read(game);
        assertThat(statistics)
                .isInstanceOfSatisfying(
                        com.videogameplatform.ratings.application.RatingStatistics.Available.class,
                        available -> {
                            assertThat(available.count()).isEqualTo(1);
                            assertThat(available.mean()).isEqualByComparingTo("6.0");
                        });
    }

    @Test
    void outerRollbackNeverPublishesSuccessfulActivity() {
        double before = activity("created");
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            store.create(
                                    user,
                                    game,
                                    direct(6),
                                    Map.of(),
                                    NOW,
                                    UUID.randomUUID().toString());
                            assertThat(activity("created")).isEqualTo(before);
                            status.setRollbackOnly();
                        });
        assertThat(store.find(user, game)).isEmpty();
        assertThat(activity("created")).isEqualTo(before);
    }

    @Test
    void directFixtureWritesAreNotObservedCommands() {
        double before = activity("created");
        admin.update(
                "INSERT INTO ratings.rating(user_id, game_id, value) VALUES (?, ?, 6)",
                UUID.fromString(user),
                UUID.fromString(game));
        assertThat(activity("created")).isEqualTo(before);
        assertThat(meters.find("ratings.personal.activity").counters())
                .hasSize(3)
                .allSatisfy(
                        counter ->
                                assertThat(counter.getId().getTags())
                                        .hasSize(1)
                                        .allSatisfy(
                                                tag ->
                                                        assertThat(tag.getKey())
                                                                .isEqualTo("action")));
    }

    private String platform() {
        UUID id = UUID.randomUUID();
        admin.update(
                "INSERT INTO catalogue.platform(platform_id, code, display_name) VALUES (?, ?, ?)",
                id,
                "platform-" + id,
                "Platform " + id);
        return id.toString();
    }

    private Map<String, String> labels() {
        return Map.of(ps5, "PlayStation 5", switchConsole, "Nintendo Switch", pc, "PC");
    }

    private static RatingScore direct(int value) {
        return new RatingScore(new RatingValue(value), List.of());
    }

    private static RatingScore platforms(PlatformScore... platforms) {
        return new RatingScore(null, List.of(platforms));
    }

    private static PlatformScore scored(String platformId, int value) {
        return new PlatformScore(platformId, new RatingValue(value));
    }

    private static PlatformScore played(String platformId) {
        return new PlatformScore(platformId, null);
    }

    /** The production read adapter over one runtime connection pool and its own snapshot. */
    private JdbcRatingStatisticsReadAdapter runtimeStatistics() {
        var runtime =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        var snapshot =
                new TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                runtime));
        snapshot.setReadOnly(true);
        return new JdbcRatingStatisticsReadAdapter(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(runtime),
                snapshot);
    }

    private double activity(String action) {
        return meters.get("ratings.personal.activity").tag("action", action).counter().count();
    }

    private Callable<Object> update(CountDownLatch start, String version, int value) {
        return () -> {
            start.await();
            try {
                return store.update(
                        user,
                        game,
                        direct(value),
                        Map.of(),
                        NOW.plusSeconds(value),
                        version,
                        UUID.randomUUID().toString());
            } catch (RuntimeException exception) {
                return exception;
            }
        };
    }
}
