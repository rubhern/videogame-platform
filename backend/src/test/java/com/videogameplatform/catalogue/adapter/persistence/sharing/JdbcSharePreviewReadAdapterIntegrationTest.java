package com.videogameplatform.catalogue.adapter.persistence.sharing;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.sql.SQLException;
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
class JdbcSharePreviewReadAdapterIntegrationTest {
    private static final String DATABASE_NAME =
            PostgreSqlTestDatabase.isolatedDatabaseName("share_preview_adapter");
    private static final UUID DEATH_STRANDING =
            UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_GAME = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID PLAYSTATION_5 =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    // One literal statement: the plan query is never built by concatenation.
    private static final String GAME_READ_PLAN =
            """
            EXPLAIN SELECT g.slug FROM catalogue.game_snapshot g
            JOIN catalogue.catalogue_publication cp
              ON cp.publication_id = g.publication_id AND cp.is_current
            WHERE g.game_id = '30000000-0000-4000-8000-000000000001'
            """;
    private static JdbcTemplate admin;
    private static JdbcTemplate runtime;
    private static JdbcSharePreviewReadAdapter adapter;

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
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                                PostgreSqlTestDatabase.migratorUsername(),
                                PostgreSqlTestDatabase.migratorPassword()));
        DataSource runtimeSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        runtime = new JdbcTemplate(runtimeSource);
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(runtimeSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        adapter =
                new JdbcSharePreviewReadAdapter(
                        new NamedParameterJdbcTemplate(runtimeSource), transaction);
    }

    @Test
    void readsOnePublishedGameWithItsCoverReference() {
        var game = adapter.findGame(DEATH_STRANDING).orElseThrow();

        assertThat(game.gameId()).isEqualTo(DEATH_STRANDING.toString());
        assertThat(game.slug()).isEqualTo("death-stranding-2-on-the-beach");
        assertThat(game.canonicalTitle()).isEqualTo("Death Stranding 2: On the Beach");
        assertThat(game.cover()).isInstanceOf(CatalogueCoverReference.Product.class);
    }

    @Test
    void prefersTheLocalSpanishTranslationOfTheSummary() {
        admin.update(
                "UPDATE catalogue.game_snapshot SET summary_kind='sourced', summary_text='Share"
                        + " source.', summary_language='en', summary_source_kind='external_provider',"
                        + " summary_source_name='IGDB', summary_source_entity_type='game_summary'"
                        + " WHERE game_id=?",
                SECOND_GAME);
        assertThat(adapter.findGame(SECOND_GAME).orElseThrow().summary())
                .isEqualTo("Share source.");

        admin.update(
                "INSERT INTO catalogue.content_translation(fingerprint,source_text,translated_text,"
                        + "runtime_revision,translated_at) VALUES(catalogue.spanish_source_fingerprint("
                        + "'Share source.'),'Share source.','Resumen para compartir.','fixture-v1',"
                        + "clock_timestamp()) ON CONFLICT DO NOTHING");
        admin.update(
                "INSERT INTO catalogue.game_summary_translation(game_id,fingerprint) VALUES(?,"
                        + "catalogue.spanish_source_fingerprint('Share source.'))",
                SECOND_GAME);

        assertThat(adapter.findGame(SECOND_GAME).orElseThrow().summary())
                .isEqualTo("Resumen para compartir.");
    }

    @Test
    void theStoredNoSummaryNoticeReadsAsNoSummaryAndRealEditorialTextIsKept() {
        admin.update(
                "UPDATE catalogue.game_snapshot SET summary_kind='editorial', summary_text="
                        + "'Todavía no hay un resumen curado para este juego.', summary_language='es',"
                        + " summary_source_kind=NULL, summary_source_name=NULL,"
                        + " summary_source_entity_type=NULL WHERE game_id=?",
                DEATH_STRANDING);
        assertThat(adapter.findGame(DEATH_STRANDING).orElseThrow().summary()).isNull();

        admin.update(
                "UPDATE catalogue.game_snapshot SET summary_text='Un viaje por una América rota.'"
                        + " WHERE game_id=?",
                DEATH_STRANDING);
        assertThat(adapter.findGame(DEATH_STRANDING).orElseThrow().summary())
                .isEqualTo("Un viaje por una América rota.");
    }

    @Test
    void anUnpublishedGameIsAbsent() {
        assertThat(adapter.findGame(UUID.fromString("30000000-0000-4000-8000-0000000000ff")))
                .isEmpty();
    }

    @Test
    void readsAPlatformNameByItsIdentifier() {
        assertThat(adapter.findPlatformName(PLAYSTATION_5)).contains("PlayStation 5");
        assertThat(
                        adapter.findPlatformName(
                                UUID.fromString("10000000-0000-4000-8000-0000000000ff")))
                .isEmpty();
    }

    @Test
    void theGameReadIsServedByTheUniqueGameIndex() {
        // The seed is too small for the planner to prefer an index on its own; disabling
        // sequential scans proves an index serves the single-row read.
        String plan =
                String.join(
                        "\n",
                        runtime.execute(
                                (java.sql.Connection connection) -> {
                                    try (var statement = connection.createStatement()) {
                                        statement.execute("SET enable_seqscan = off");
                                        try (var rows = statement.executeQuery(GAME_READ_PLAN)) {
                                            var lines = new java.util.ArrayList<String>();
                                            while (rows.next()) lines.add(rows.getString(1));
                                            statement.execute("RESET enable_seqscan");
                                            return lines;
                                        }
                                    }
                                }));

        assertThat(plan).contains("game_id").containsPattern("Index (Only )?Scan");
    }
}
