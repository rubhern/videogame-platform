package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcReleaseBrowseReadAdapter;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase.Sort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL evidence for #456: IGDB Port and Expanded Game works become Games of their own, while
 * every other deferred type stays deferred. Provider identifiers, types, titles and dates follow
 * what IGDB returned on 2026-11-06 for World of Warcraft: Forever, Pikmin 4: Nintendo Switch 2
 * Edition + Dandori Academy, and the port titled exactly like its main game, Pikmin.
 */
class PortAndExpandedGameImportIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");
    private static final LocalDate FROM = LocalDate.parse("2026-11-01");
    private static final LocalDate TO = LocalDate.parse("2026-11-30");
    private static final CatalogueSynchronizationRequest WINDOW =
            new CatalogueSynchronizationRequest(FROM, TO);
    private static final String MAIN_GAME = "2239";
    private static final String SAME_TITLE_PORT = "84696";
    private static final String EXPANDED_GAME = "417169";
    private static final String PORT = "417650";

    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;
    private JdbcReleaseBrowseReadAdapter explorer;
    private FixtureProvider provider;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("port_import");
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
        store =
                new JdbcCatalogueSynchronizationStore(
                        new NamedParameterJdbcTemplate(jdbc),
                        new TransactionTemplate(new JdbcTransactionManager(ds)),
                        "IGDB",
                        gameId -> {});
        var read = new TransactionTemplate(new JdbcTransactionManager(ds));
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        explorer = new JdbcReleaseBrowseReadAdapter(new NamedParameterJdbcTemplate(jdbc), read);
        provider = new FixtureProvider();
    }

    @Test
    void portsAndExpandedGamesBecomeGamesWithTheirOwnReleases() {
        provider.put(
                work(PORT, "World of Warcraft: Forever", ProviderWorkType.PORT, 10L, "2026-11-04"));
        provider.put(
                work(
                        EXPANDED_GAME,
                        "Pikmin 4: Nintendo Switch 2 Edition + Dandori Academy",
                        ProviderWorkType.EXPANDED_GAME,
                        null,
                        "2026-11-12"));

        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(report.counters().createdGames()).isEqualTo(2);
        assertThat(report.counters().deferredGames()).isZero();
        assertThat(store.loadGame(PORT, 25).orElseThrow().releases()).hasSize(1);
        assertThat(store.loadGame(EXPANDED_GAME, 25).orElseThrow().releases()).hasSize(1);
        assertThat(explorerTitles())
                .contains(
                        "World of Warcraft: Forever",
                        "Pikmin 4: Nintendo Switch 2 Edition + Dandori Academy");
    }

    @Test
    void identicalTitlesImportWithoutFailingAndKeepDistinctSlugs() {
        provider.put(work(MAIN_GAME, "Pikmin", ProviderWorkType.MAIN_GAME, null, "2026-11-03"));
        provider.put(work(SAME_TITLE_PORT, "Pikmin", ProviderWorkType.PORT, null, "2026-11-03"));

        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(report.counters().failedGames()).isZero();
        String mainSlug = store.loadGame(MAIN_GAME, 25).orElseThrow().slug();
        String portSlug = store.loadGame(SAME_TITLE_PORT, 25).orElseThrow().slug();
        assertThat(mainSlug).startsWith("pikmin-");
        assertThat(portSlug).startsWith("pikmin-").isNotEqualTo(mainSlug);
    }

    @Test
    void repeatedSynchronizationKeepsOneGamePerProviderReference() {
        provider.put(
                work(PORT, "World of Warcraft: Forever", ProviderWorkType.PORT, 10L, "2026-11-04"));
        synchronize();
        var before = store.loadGame(PORT, 25).orElseThrow();

        var again = synchronize();

        assertThat(again.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(again.counters().createdGames()).isZero();
        assertThat(again.counters().unchangedGames()).isEqualTo(1);
        assertThat(store.loadGame(PORT, 25)).contains(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogue.game", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void everyOtherDeferredTypeStaysDeferredWithoutFailing() {
        var deferred =
                List.of(
                        ProviderWorkType.ADD_ON,
                        ProviderWorkType.EXPANSION,
                        ProviderWorkType.EPISODE,
                        ProviderWorkType.SEASON,
                        ProviderWorkType.BUNDLE,
                        ProviderWorkType.PACK,
                        ProviderWorkType.UPDATE,
                        ProviderWorkType.MOD,
                        ProviderWorkType.FORK,
                        ProviderWorkType.UNKNOWN);
        for (int i = 0; i < deferred.size(); i++) {
            provider.put(
                    work(String.valueOf(100 + i), "Work " + i, deferred.get(i), 50L, "2026-11-05"));
        }

        var report = synchronize();

        assertThat(report.counters().deferredGames()).isEqualTo(deferred.size());
        assertThat(report.counters().failedGames()).isZero();
        assertThat(report.counters().createdGames()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogue.game", Integer.class))
                .isZero();
    }

    @Test
    void importedWorksRankInTheExplorerHypeOrderWithNoSpecialHandling() {
        provider.put(work(MAIN_GAME, "Main Game", ProviderWorkType.MAIN_GAME, 40L, "2026-11-03"));
        provider.put(work(PORT, "Port", ProviderWorkType.PORT, 50L, "2026-11-04"));
        provider.put(
                work(EXPANDED_GAME, "Expanded", ProviderWorkType.EXPANDED_GAME, 30L, "2026-11-05"));
        provider.put(work("500", "Without Hypes", ProviderWorkType.PORT, null, "2026-11-02"));

        synchronize();

        assertThat(explorerTitles())
                .containsExactly("Port", "Main Game", "Expanded", "Without Hypes");
    }

    private List<String> explorerTitles() {
        return explorer
                .findPublishedReleases(
                        new ReleaseBrowseReadPort.Criteria(
                                new ReleaseBrowseReadPort.Window(FROM, TO),
                                LocalDate.parse("2026-11-01"),
                                Sort.HYPE,
                                List.of(),
                                List.of(),
                                new ReleaseBrowseReadPort.Pagination(1, 50, 0),
                                false,
                                25))
                .orElseThrow()
                .items()
                .stream()
                .map(ReleaseBrowseReadPort.Item::canonicalTitle)
                .toList();
    }

    private CatalogueSynchronizationReport synchronize() {
        return new CatalogueSynchronizationService(
                        store,
                        provider,
                        Clock.fixed(NOW, ZoneId.of("Europe/Madrid")),
                        new SynchronizationPolicy(10, 25, 50, Duration.ofMinutes(30)),
                        new CoverSelectionPolicy(
                                "/assets/covers/fallback.svg", "VideoGame Platform"),
                        SynchronizationProgress.NONE)
                .synchronize(WINDOW);
    }

    private static CatalogueProviderPort.ProviderWork work(
            String id, String title, ProviderWorkType type, Long hypes, String day) {
        return new CatalogueProviderPort.ProviderWork(
                id,
                title,
                type,
                NOW,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(
                        new CatalogueProviderPort.ProviderRelease(
                                "9" + id,
                                new CatalogueProviderPort.ProviderPlatform(
                                        "6", "Windows PC", "win"),
                                Optional.of(
                                        new CatalogueProviderPort.ProviderRegion("8", "worldwide")),
                                new ReleaseDate.Day(LocalDate.parse(day)),
                                ProviderReleaseSignal.NONE,
                                ReleaseStage.FULL_RELEASE)),
                List.of(),
                Optional.of(
                        new CatalogueProviderPort.ProviderFeaturedEvidence(
                                Optional.of(LocalDate.parse(day)),
                                false,
                                Optional.ofNullable(hypes))));
    }

    private static final class FixtureProvider implements CatalogueProviderPort {
        final Map<String, ProviderWork> works = new TreeMap<>();

        void put(ProviderWork work) {
            works.put(work.providerId(), work);
        }

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
                    works.keySet().stream()
                            .filter(id -> Long.parseLong(id) > afterGameId)
                            .sorted(java.util.Comparator.comparingLong(Long::parseLong))
                            .toList();
            return new ReleasePage(
                    page,
                    page.size(),
                    page.isEmpty() ? afterGameId : Long.parseLong(page.getLast()),
                    true,
                    ProviderCallStatistics.none());
        }

        @Override
        public ProviderWorkBatch fetchWorks(List<String> ids) {
            return new ProviderWorkBatch(
                    new ArrayList<>(ids.stream().map(works::get).filter(Objects::nonNull).toList()),
                    ProviderCallStatistics.none());
        }

        @Override
        public AttentionBatch attention(List<String> ids) {

            return new AttentionBatch(Map.of(), ProviderCallStatistics.none());
        }

        @Override
        public LogoBatch logos(List<String> ids) {
            return new LogoBatch(Map.of(), ProviderCallStatistics.none());
        }
    }
}
