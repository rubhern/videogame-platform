package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.persistence.details.JdbcGameDetailsReadAdapter;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderCompany;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderGameDetails;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderSummary;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderTerm;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL evidence for #233: synchronization acquires a Game's summary, developers, publishers,
 * genres and game modes as provider-independent local state; repeated answers are idempotent, a
 * changed answer reconciles without changing identity, and an invalid answer or failed Game keeps
 * the last valid details. The public read then serves them from PostgreSQL alone.
 */
class GameDetailMetadataSynchronizationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final CatalogueSynchronizationRequest WINDOW =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
    private static final String NO_SUMMARY = "Todavía no hay un resumen curado para este juego.";

    private static final ProviderCompany STUDIO = new ProviderCompany("3045", "Sucker Punch");
    private static final ProviderCompany PUBLISHER = new ProviderCompany("10100", "Sony");
    private static final ProviderCompany SELF = new ProviderCompany("5050", "Self Made Games");
    private static final ProviderTerm ADVENTURE = new ProviderTerm("31", "Adventure", "adventure");
    private static final ProviderTerm RPG =
            new ProviderTerm("12", "Role-playing (RPG)", "role-playing-rpg");
    private static final ProviderTerm SINGLE =
            new ProviderTerm("1", "Single player", "single-player");
    private static final ProviderTerm MULTI = new ProviderTerm("2", "Multiplayer", "multiplayer");
    private static final ProviderTerm COOP = new ProviderTerm("3", "Co-operative", "co-operative");

    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;
    private JdbcGameDetailsReadAdapter reads;
    private FixtureProvider provider;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("details");
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var ds =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(database),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new JdbcTransactionManager(ds));
        store =
                new JdbcCatalogueSynchronizationStore(
                        new NamedParameterJdbcTemplate(jdbc), tx, "IGDB", gameId -> {});
        reads = new JdbcGameDetailsReadAdapter(new NamedParameterJdbcTemplate(jdbc), tx);
        provider = new FixtureProvider();
        provider.rows = List.of("100");
    }

    @Test
    void acquiresProviderIndependentDetailsAndAResyncIsIdempotent() {
        provider.works.put(
                "100",
                work(
                        "100",
                        details(
                                "A lone warrior.",
                                List.of(STUDIO, SELF),
                                List.of(PUBLISHER, SELF),
                                List.of(RPG, ADVENTURE),
                                List.of(SINGLE, MULTI, COOP))));

        assertThat(synchronize(0).counters().createdGames()).isEqualTo(1);

        String gameId = gameId("100");
        var game = reads.find(gameId).orElseThrow();
        assertThat(game.summary())
                .isEqualTo(
                        new GameDetailsResult.Summary(
                                "sourced",
                                "A lone warrior.",
                                "en",
                                new com.videogameplatform.catalogue.application.releases
                                        .BrowseReleasesResult.Provenance(
                                        com.videogameplatform.catalogue.application.releases
                                                .BrowseReleasesResult.Source.EXTERNAL_PROVIDER,
                                        "IGDB",
                                        "game_summary")));
        // Product identity, never provider IDs; a company credited with both roles is one company.
        assertThat(game.developers())
                .extracting(GameDetailsResult.Company::name)
                .containsExactly("Self Made Games", "Sucker Punch");
        assertThat(game.publishers())
                .extracting(GameDetailsResult.Company::name)
                .containsExactly("Self Made Games", "Sony");
        assertThat(game.developers().getFirst().companyId())
                .isEqualTo(game.publishers().getFirst().companyId())
                .doesNotContain("5050");
        assertThat(game.genres())
                .containsExactly(
                        new GameDetailsResult.Term("adventure", "Adventure"),
                        new GameDetailsResult.Term("role-playing-rpg", "Role-playing (RPG)"));
        assertThat(game.gameModes())
                .extracting(GameDetailsResult.Term::code)
                .containsExactly("co-operative", "multiplayer", "single-player");
        assertThat(count("company")).isEqualTo(3);
        assertThat(count("game_company")).isEqualTo(4);

        Map<String, Object> before = snapshot();
        String revision = version();
        var repeated = synchronize(1);

        assertThat(repeated.counters().unchangedGames()).isEqualTo(1);
        assertThat(repeated.counters().detailsUnavailableGames()).isZero();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(version()).isEqualTo(revision);
        assertThat(reads.find(gameId)).contains(game);
    }

    @Test
    void aChangedAnswerReconcilesLinksWithoutChangingAnyIdentity() {
        provider.works.put(
                "100",
                work(
                        "100",
                        details(
                                "First.",
                                List.of(STUDIO),
                                List.of(PUBLISHER),
                                List.of(ADVENTURE, RPG),
                                List.of(SINGLE))));
        synchronize(0);
        String gameId = gameId("100");
        UUID studio = companyId("3045");
        UUID adventure = termId("genre", "31");
        String revision = version();

        provider.works.put(
                "100",
                work(
                        "100",
                        details(
                                "Second.",
                                List.of(new ProviderCompany("3045", "Sucker Punch Productions")),
                                List.of(SELF),
                                List.of(new ProviderTerm("31", "Renamed adventure", "renamed")),
                                List.of(SINGLE, COOP))));
        var report = synchronize(1);

        assertThat(report.counters().updatedGames()).isEqualTo(1);
        assertThat(version()).isNotEqualTo(revision);
        assertThat(gameId("100")).isEqualTo(gameId);
        assertThat(companyId("3045")).isEqualTo(studio);
        assertThat(termId("genre", "31")).isEqualTo(adventure);
        var game = reads.find(gameId).orElseThrow();
        assertThat(game.summary().text()).isEqualTo("Second.");
        // A company name is provider evidence and follows the valid answer; a genre label is
        // catalogue taxonomy set on first acquisition, as for platforms.
        assertThat(game.developers())
                .containsExactly(
                        new GameDetailsResult.Company(
                                studio.toString(), "Sucker Punch Productions"));
        assertThat(game.publishers())
                .extracting(GameDetailsResult.Company::name)
                .containsExactly("Self Made Games");
        assertThat(game.genres())
                .containsExactly(new GameDetailsResult.Term("adventure", "Adventure"));
        assertThat(game.gameModes())
                .extracting(GameDetailsResult.Term::code)
                .containsExactly("co-operative", "single-player");
        // Unlinked product entities stay; only this Game's links changed.
        assertThat(count("company")).isEqualTo(3);
        assertThat(count("genre")).isEqualTo(2);
    }

    @Test
    void aValidAnswerWithoutMetadataClearsProviderDetailsWithoutInventingValues() {
        provider.works.put(
                "100",
                work(
                        "100",
                        details(
                                "Text.",
                                List.of(STUDIO),
                                List.of(PUBLISHER),
                                List.of(ADVENTURE),
                                List.of(SINGLE))));
        synchronize(0);

        provider.works.put(
                "100",
                work(
                        "100",
                        new ProviderGameDetails(
                                Optional.empty(), List.of(), List.of(), List.of(), List.of())));
        synchronize(1);

        var game = reads.find(gameId("100")).orElseThrow();
        assertThat(game.summary())
                .isEqualTo(new GameDetailsResult.Summary("editorial", NO_SUMMARY, "es", null));
        assertThat(game.developers()).isEmpty();
        assertThat(game.publishers()).isEmpty();
        assertThat(game.genres()).isEmpty();
        assertThat(game.gameModes()).isEmpty();
        assertThat(count("game")).isEqualTo(1);
    }

    @Test
    void invalidDetailsKeepTheLastValidDetailsWhileTheRestOfTheGameStillSynchronizes() {
        provider.works.put(
                "100",
                work(
                        "100",
                        details(
                                "Valid.",
                                List.of(STUDIO),
                                List.of(PUBLISHER),
                                List.of(ADVENTURE),
                                List.of(SINGLE))));
        synchronize(0);
        String gameId = gameId("100");
        var valid = reads.find(gameId).orElseThrow();

        // The adapter found the detail metadata incoherent: no details cross the port.
        provider.works.put(
                "100",
                new ProviderWork(
                        "100",
                        "Renamed game",
                        ProviderWorkType.MAIN_GAME,
                        NOW,
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(release("10")),
                        List.of(ProviderMappingFailure.DETAILS_INVALID),
                        Optional.empty(),
                        Optional.empty()));
        var report = synchronize(1);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(report.counters().detailsUnavailableGames()).isEqualTo(1);
        var kept = reads.find(gameId).orElseThrow();
        assertThat(kept.canonicalTitle()).isEqualTo("Renamed game");
        assertThat(kept.summary()).isEqualTo(valid.summary());
        assertThat(kept.developers()).isEqualTo(valid.developers());
        assertThat(kept.publishers()).isEqualTo(valid.publishers());
        assertThat(kept.genres()).isEqualTo(valid.genres());
        assertThat(kept.gameModes()).isEqualTo(valid.gameModes());
    }

    @Test
    void aFailedGameOrProviderFailureKeepsTheLastValidDetails() {
        provider.works.put(
                "100",
                work(
                        "100",
                        details(
                                "Valid.",
                                List.of(STUDIO),
                                List.of(PUBLISHER),
                                List.of(ADVENTURE),
                                List.of(SINGLE))));
        synchronize(0);
        Map<String, Object> before = snapshot();
        var changed =
                details("Changed.", List.of(SELF), List.of(SELF), List.of(RPG), List.of(COOP));

        // A duplicate release reference rejects the whole aggregate, details included.
        provider.works.put("100", work("100", changed, release("10"), release("10")));
        assertThat(synchronize(1).outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(snapshot()).isEqualTo(before);

        // A provider failure for the Game never reaches persistence.
        provider.works.put("100", work("100", changed));
        provider.failure =
                new ProviderRequestException(
                        ProviderFailureCode.PROVIDER_UNAVAILABLE, ProviderCallStatistics.none());
        assertThat(synchronize(2).outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void synchronizationNeverReplacesProductEditorialOrOtherSourceSummaries() {
        provider.works.put(
                "100",
                work("100", details("Provider.", List.of(), List.of(), List.of(), List.of())));
        synchronize(0);
        jdbc.update(
                "UPDATE catalogue.game_snapshot SET summary_kind='editorial',"
                        + " summary_text='Resumen editorial del producto.', summary_language='es',"
                        + " summary_source_kind=NULL, summary_source_name=NULL,"
                        + " summary_source_entity_type=NULL");
        provider.works.put(
                "100",
                work(
                        "100",
                        details("Provider again.", List.of(), List.of(), List.of(), List.of())));
        synchronize(1);
        assertThat(reads.find(gameId("100")).orElseThrow().summary().text())
                .isEqualTo("Resumen editorial del producto.");

        jdbc.update(
                "UPDATE catalogue.game_snapshot SET summary_kind='sourced',"
                        + " summary_text='Official text.', summary_language='en',"
                        + " summary_source_kind='official_source', summary_source_name='Publisher',"
                        + " summary_source_entity_type='press_kit'");
        provider.works.put(
                "100",
                work(
                        "100",
                        new ProviderGameDetails(
                                Optional.empty(), List.of(), List.of(), List.of(), List.of())));
        synchronize(2);
        assertThat(reads.find(gameId("100")).orElseThrow().summary().text())
                .isEqualTo("Official text.");
    }

    @Test
    void sharedProviderEntitiesResolveToOneProductEntityAcrossGames() {
        provider.rows = List.of("100", "101");
        provider.works.put(
                "100",
                work("100", details(null, List.of(STUDIO), List.of(), List.of(RPG), List.of())));
        provider.works.put(
                "101",
                work(
                        "101",
                        details(
                                null,
                                List.of(new ProviderCompany("3045", "Sucker Punch")),
                                List.of(STUDIO),
                                List.of(RPG),
                                List.of())));
        synchronize(0);

        assertThat(count("company")).isEqualTo(1);
        assertThat(count("company_external_reference")).isEqualTo(1);
        assertThat(count("genre")).isEqualTo(1);
        assertThat(count("game_company")).isEqualTo(3);
        assertThat(reads.find(gameId("101")).orElseThrow().publishers())
                .extracting(GameDetailsResult.Company::companyId)
                .containsExactly(companyId("3045").toString());
    }

    @Test
    void theDatabaseRejectsDuplicateLinksAndUnknownCompanyRoles() {
        provider.works.put(
                "100",
                work("100", details(null, List.of(STUDIO), List.of(), List.of(RPG), List.of())));
        synchronize(0);
        UUID game = UUID.fromString(gameId("100"));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO catalogue.game_company VALUES (?, 'developer', ?)",
                                        game,
                                        companyId("3045")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO catalogue.game_company VALUES (?, 'porting', ?)",
                                        game,
                                        companyId("3045")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO catalogue.game_genre VALUES (?, ?)",
                                        game,
                                        termId("genre", "12")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    private CatalogueSynchronizationReport synchronize(int secondsLater) {
        return new CatalogueSynchronizationService(
                        store,
                        provider,
                        Clock.fixed(NOW.plusSeconds(secondsLater), ZoneId.of("Europe/Madrid")),
                        new SynchronizationPolicy(10, 25, 50, Duration.ofMinutes(30)),
                        new CoverSelectionPolicy(
                                "/assets/covers/fallback.svg", "VideoGame Platform"),
                        SynchronizationProgress.NONE)
                .synchronize(WINDOW);
    }

    private static ProviderGameDetails details(
            String summary,
            List<ProviderCompany> developers,
            List<ProviderCompany> publishers,
            List<ProviderTerm> genres,
            List<ProviderTerm> modes) {
        return new ProviderGameDetails(
                Optional.ofNullable(summary).map(text -> new ProviderSummary(text, "en")),
                developers,
                publishers,
                genres,
                modes);
    }

    private static ProviderWork work(
            String id, ProviderGameDetails details, ProviderRelease... releases) {
        return new ProviderWork(
                id,
                "Game " + id,
                ProviderWorkType.MAIN_GAME,
                NOW,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                releases.length == 0 ? List.of(release(id + "0")) : List.of(releases),
                List.of(),
                Optional.empty(),
                Optional.of(details));
    }

    private static ProviderRelease release(String id) {
        return new ProviderRelease(
                id,
                new ProviderPlatform("6", "PC (Microsoft Windows)", "win"),
                Optional.of(new ProviderRegion("8", "worldwide")),
                new ReleaseDate.Day(LocalDate.parse("2026-05-01")),
                ProviderReleaseSignal.NONE,
                ReleaseStage.UNKNOWN);
    }

    /** Every stored detail row of the catalogue, to prove nothing was rewritten. */
    private Map<String, Object> snapshot() {
        Map<String, Object> state = new HashMap<>();
        state.put(
                "summary",
                jdbc.queryForList(
                        "SELECT game_id, summary_kind, summary_text, summary_language,"
                                + " summary_source_kind, summary_source_name,"
                                + " summary_source_entity_type FROM catalogue.game_snapshot"
                                + " ORDER BY game_id"));
        state.put("companies", jdbc.queryForList("SELECT * FROM catalogue.company ORDER BY 1"));
        state.put(
                "credits",
                jdbc.queryForList("SELECT * FROM catalogue.game_company ORDER BY 1, 2, 3"));
        state.put("genres", jdbc.queryForList("SELECT * FROM catalogue.genre ORDER BY 1"));
        state.put(
                "gameGenres",
                jdbc.queryForList("SELECT * FROM catalogue.game_genre ORDER BY 1, 2"));
        state.put("modes", jdbc.queryForList("SELECT * FROM catalogue.game_mode ORDER BY 1"));
        state.put(
                "gameModes",
                jdbc.queryForList("SELECT * FROM catalogue.game_game_mode ORDER BY 1, 2"));
        return state;
    }

    private String gameId(String providerId) {
        return jdbc.queryForObject(
                "SELECT game_id::text FROM catalogue.game_external_reference"
                        + " WHERE provider='IGDB' AND provider_id=?",
                String.class,
                providerId);
    }

    private UUID companyId(String providerId) {
        return jdbc.queryForObject(
                "SELECT company_id FROM catalogue.company_external_reference"
                        + " WHERE provider='IGDB' AND provider_id=?",
                UUID.class,
                providerId);
    }

    private UUID termId(String kind, String providerId) {
        return jdbc.queryForObject(
                "SELECT "
                        + kind
                        + "_id FROM catalogue."
                        + kind
                        + "_external_reference WHERE provider='IGDB' AND provider_id=?",
                UUID.class,
                providerId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM catalogue." + table, Integer.class);
    }

    private String version() {
        return jdbc.queryForObject(
                "SELECT catalogue_version FROM catalogue.catalogue_publication", String.class);
    }

    private static final class FixtureProvider implements CatalogueProviderPort {
        List<String> rows = List.of();
        Map<String, ProviderWork> works = new HashMap<>();
        ProviderRequestException failure;

        @Override
        public String providerName() {
            return "IGDB";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public ReleasePage releaseGames(LocalDate from, LocalDate to, long afterGameId, int limit) {
            List<String> page =
                    rows.stream().filter(id -> Long.parseLong(id) > afterGameId).toList();
            return new ReleasePage(
                    page,
                    page.size(),
                    page.isEmpty() ? afterGameId : Long.parseLong(page.getLast()),
                    true,
                    ProviderCallStatistics.none());
        }

        @Override
        public ProviderWorkBatch fetchWorks(List<String> ids) {
            if (failure != null) {
                throw failure;
            }
            return new ProviderWorkBatch(
                    new ArrayList<>(ids.stream().map(works::get).filter(Objects::nonNull).toList()),
                    ProviderCallStatistics.none());
        }

        @Override
        public LogoBatch logos(List<String> ids) {
            return new LogoBatch(Map.of(), ProviderCallStatistics.none());
        }
    }
}
