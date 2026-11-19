package com.videogameplatform.catalogue.adapter.persistence.details;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.details.IndexableGame;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Execution(ExecutionMode.SAME_THREAD)
class JdbcIndexableGamesReadAdapterIntegrationTest {

    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("indexable_games_adapter");
    private static JdbcIndexableGamesReadAdapter adapter;
    private static List<String> currentGames;

    @BeforeAll
    static void prepareDatabase() throws SQLException {
        PostgreSqlTestDatabase.createDatabase(DATABASE_NAME);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration", "classpath:db/dev-seed")
                .load()
                .migrate();
        DataSource runtime =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        var jdbc = new JdbcTemplate(runtime);
        currentGames =
                jdbc.queryForList(
                        """
                        SELECT game_id::text FROM catalogue.game_snapshot
                        WHERE publication_id = (SELECT publication_id
                                                FROM catalogue.catalogue_publication
                                                WHERE is_current)
                        ORDER BY game_id
                        """,
                        String.class);
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(runtime));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        adapter =
                new JdbcIndexableGamesReadAdapter(
                        new NamedParameterJdbcTemplate(runtime), transaction);
    }

    @Test
    void partitionsTheCurrentPublicationIntoDisjointCompleteIdentifierRanges() {
        assertThat(currentGames).hasSizeGreaterThan(4);

        var starts = adapter.partitionStarts(2, 1_000);

        List<String> listed = new ArrayList<>();
        for (var start : starts) {
            var partition = adapter.partition(UUID.fromString(start), 2);
            assertThat(partition).isNotEmpty().hasSizeLessThanOrEqualTo(2);
            assertThat(partition.getFirst().gameId()).isEqualTo(start);
            partition.stream().map(IndexableGame::gameId).forEach(listed::add);
        }
        // Every current game exactly once, in identifier order.
        assertThat(listed).containsExactlyElementsOf(currentGames);
        assertThat(starts).hasSize((currentGames.size() + 1) / 2);
    }

    @Test
    void listsTheStoredCanonicalSlugOfEachGame() {
        var first = adapter.partition(UUID.fromString(currentGames.getFirst()), 1);

        assertThat(first)
                .singleElement()
                .satisfies(
                        game -> {
                            assertThat(game.gameId()).isEqualTo(currentGames.getFirst());
                            assertThat(game.slug()).matches("[a-z0-9]+(?:-[a-z0-9]+)*");
                        });
    }

    @Test
    void boundsTheNumberOfPartitionStarts() {
        assertThat(adapter.partitionStarts(1, 3))
                .containsExactlyElementsOf(currentGames.subList(0, 3));
    }

    @Test
    void aStaleStartStillListsItsBoundedRangeAndOnePastEveryGameIsEmpty() {
        var between = UUID.fromString(currentGames.get(1));

        assertThat(adapter.partition(between, 2))
                .extracting(IndexableGame::gameId)
                .containsExactlyElementsOf(currentGames.subList(1, 3));
        assertThat(adapter.partition(UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"), 2))
                .isEmpty();
    }
}
