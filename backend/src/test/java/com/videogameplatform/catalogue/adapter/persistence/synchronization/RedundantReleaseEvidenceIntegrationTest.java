package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
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
 * PostgreSQL evidence for #313. IGDB restates a Full Release with a second, stage-less record on
 * the same platform, region and day (Octopath Traveler II, Back to the Dawn, Hot Wheels Infinite
 * Rush, Shockwave Triggers, Hello, Luigi!). Both describe one commercial Release: the staged record
 * is kept and the stage-less one is redundant, so the Game is acquired instead of failing on the
 * release tuple constraint. Every other shared tuple still fails only its Game, with an explicit
 * reason. Provider references and dates below are the ones IGDB returned for Octopath Traveler II.
 */
class RedundantReleaseEvidenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    private static final CatalogueSynchronizationRequest WINDOW =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-09-17"), LocalDate.parse("2026-11-12"));
    private static final String GAME = "217554";
    private static final String STAGED = "941948";
    private static final String STAGELESS = "942928";
    private static final String SWITCH_2 = "508";
    private static final String WINDOWS = "6";
    private static final String SWITCH_2_DAY = "2026-10-01";

    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;
    private FixtureProvider provider;
    private List<String> failures;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("redundant");
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
        provider = new FixtureProvider();
        failures = new ArrayList<>();
    }

    @Test
    void acquiresAPreviouslyRejectedGameKeepingTheStagedReleaseOnly() {
        provider.put(
                work(
                        GAME,
                        release(STAGED, SWITCH_2, SWITCH_2_DAY, ReleaseStage.FULL_RELEASE),
                        release("603827", WINDOWS, "2023-02-24", ReleaseStage.FULL_RELEASE),
                        release(STAGELESS, SWITCH_2, SWITCH_2_DAY, ReleaseStage.UNKNOWN)));

        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(failures).isEmpty();
        assertThat(report.counters().createdGames()).isEqualTo(1);
        assertThat(report.counters().createdReleases()).isEqualTo(2);
        assertThat(report.counters().redundantReleases()).isEqualTo(1);
        assertThat(storedStages())
                .containsExactly(
                        Map.entry("603827", "full_release"), Map.entry(STAGED, "full_release"));
        // The redundant reference leaves no trace: no Release, reference or reconciliation check.
        assertThat(count("game_release")).isEqualTo(2);
        assertThat(count("release_reconciliation")).isEqualTo(2);
        // The durable report keeps the counter.
        assertThat(store.lastRun().orElseThrow().counters().redundantReleases()).isEqualTo(1);
    }

    @Test
    void repeatedSynchronizationIsIdempotentAndKeepsEveryIdentity() {
        provider.put(stagedAndStageless());
        synchronize();
        var before = store.loadGame(GAME, 25).orElseThrow();
        String revision = version();

        var again = synchronize();

        assertThat(again.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(again.counters().unchangedGames()).isEqualTo(1);
        assertThat(again.counters().redundantReleases()).isEqualTo(1);
        assertThat(again.counters().createdReleases() + again.counters().deletedReleases())
                .isZero();
        assertThat(store.loadGame(GAME, 25)).contains(before);
        assertThat(version()).isEqualTo(revision);
    }

    @Test
    void aStagedRestatementReplacesAStoredStagelessRelease() {
        provider.put(work(GAME, stageless()));
        synchronize();
        assertThat(storedStages()).containsExactly(Map.entry(STAGELESS, "unknown"));
        UUID gameId = store.loadGame(GAME, 25).orElseThrow().gameId();

        provider.put(stagedAndStageless());
        var report = synchronize();

        // Deletion precedes creation in one Game transaction, so the staged reference takes over
        // the tuple; the Game keeps its identity.
        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(report.counters().deletedReleases()).isEqualTo(1);
        assertThat(report.counters().createdReleases()).isEqualTo(1);
        assertThat(report.counters().redundantReleases()).isEqualTo(1);
        assertThat(storedStages()).containsExactly(Map.entry(STAGED, "full_release"));
        assertThat(store.loadGame(GAME, 25).orElseThrow().gameId()).isEqualTo(gameId);
    }

    @Test
    void aStagelessReferenceReturnsWhenItsStagedCounterpartDisappears() {
        provider.put(stagedAndStageless());
        synchronize();

        provider.put(work(GAME, stageless()));
        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(report.counters().redundantReleases()).isZero();
        assertThat(report.counters().deletedReleases()).isEqualTo(1);
        assertThat(storedStages()).containsExactly(Map.entry(STAGELESS, "unknown"));
    }

    @Test
    void releasesOfDifferentStagesOnDifferentDaysStayDistinct() {
        // Hot Wheels Infinite Rush on Switch 2: Advanced Access precedes the Full Release.
        provider.put(
                work(
                        "404710",
                        release("942791", SWITCH_2, "2026-09-07", ReleaseStage.ADVANCE_ACCESS),
                        release("942786", SWITCH_2, "2026-09-10", ReleaseStage.FULL_RELEASE),
                        release("947726", SWITCH_2, "2026-09-10", ReleaseStage.UNKNOWN)));

        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(report.counters().redundantReleases()).isEqualTo(1);
        assertThat(storedStages())
                .containsExactly(
                        Map.entry("942786", "full_release"), Map.entry("942791", "advance_access"));
    }

    @Test
    void sharedTuplesWithoutOneStagedReferenceFailOnlyTheirGameExplicitly() {
        provider.put(stagedAndStageless());
        synchronize();
        var before = store.loadGame(GAME, 25).orElseThrow();
        String revision = version();

        // The stage-less record gains the same stage: two staged references, no single Release.
        provider.put(
                work(
                        GAME,
                        staged(),
                        release(STAGELESS, SWITCH_2, SWITCH_2_DAY, ReleaseStage.FULL_RELEASE)));
        // Different known stages on one tuple contradict each other.
        provider.put(
                work(
                        "300",
                        release("30", WINDOWS, "2026-10-20", ReleaseStage.EARLY_ACCESS),
                        release("31", WINDOWS, "2026-10-20", ReleaseStage.FULL_RELEASE)));
        // No stage at all: missing evidence never proves duplication.
        provider.put(
                work(
                        "400",
                        release("40", WINDOWS, "2026-10-21", ReleaseStage.UNKNOWN),
                        release("41", WINDOWS, "2026-10-21", ReleaseStage.UNKNOWN)));
        // An unrelated valid Game in the same run is committed.
        provider.put(work("500", release("50", WINDOWS, "2026-10-22", ReleaseStage.UNKNOWN)));

        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.PARTIAL);
        assertThat(report.counters().failedGames()).isEqualTo(3);
        assertThat(report.counters().createdGames()).isEqualTo(1);
        assertThat(report.counters().redundantReleases()).isZero();
        assertThat(failures)
                .containsExactlyInAnyOrder(
                        "RECONCILIATION/RELEASE_TUPLE_CONFLICT/published",
                        "RECONCILIATION/RELEASE_TUPLE_CONFLICT/unpublished",
                        "RECONCILIATION/RELEASE_TUPLE_CONFLICT/unpublished");
        assertThat(store.loadGame(GAME, 25)).contains(before);
        assertThat(store.loadGame("300", 25)).isEmpty();
        assertThat(store.loadGame("400", 25)).isEmpty();
        assertThat(store.loadGame("500", 25)).isPresent();
        assertThat(version()).isNotEqualTo(revision);
    }

    @Test
    void verifiedStagelessEvidenceIsNeverDiscarded() {
        provider.put(work(GAME, stageless()));
        synchronize();
        jdbc.update(
                "UPDATE catalogue.release_snapshot SET verification_level='verified',"
                        + " last_verified_at=now()");
        var before = store.loadGame(GAME, 25).orElseThrow();

        provider.put(stagedAndStageless());
        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(failures).containsExactly("RECONCILIATION/RELEASE_TUPLE_CONFLICT/published");
        assertThat(store.loadGame(GAME, 25)).contains(before);
    }

    @Test
    void aCollisionWithEvidenceOutsideSynchronizationOwnershipIsNamedAndRolledBack() {
        provider.put(work(GAME, stageless()));
        synchronize();
        jdbc.update(
                "UPDATE catalogue.release_snapshot SET source_kind='product_curated',"
                        + " source_name='Product', source_entity_type='curation'");
        var before = store.loadGame(GAME, 25).orElseThrow();

        // Only the staged reference is returned now. The curated Release is neither removable nor
        // redundant evidence, so PostgreSQL rejects the staged one and names the release tuple.
        provider.put(work(GAME, staged()));
        var report = synchronize();

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(failures).containsExactly("PERSISTENCE/RELEASE_TUPLE_CONFLICT/published");
        assertThat(store.loadGame(GAME, 25)).contains(before);
        assertThat(count("release_external_reference")).isEqualTo(1);
    }

    private CatalogueSynchronizationReport synchronize() {
        failures.clear();
        return new CatalogueSynchronizationService(
                        store,
                        provider,
                        Clock.fixed(NOW, ZoneId.of("Europe/Madrid")),
                        new SynchronizationPolicy(10, 25, 50, Duration.ofMinutes(30)),
                        new CoverSelectionPolicy(
                                "/assets/covers/fallback.svg", "VideoGame Platform"),
                        new RecordingProgress(failures))
                .synchronize(WINDOW);
    }

    private static ProviderWork stagedAndStageless() {
        return work(GAME, staged(), stageless());
    }

    private static ProviderRelease staged() {
        return release(STAGED, SWITCH_2, SWITCH_2_DAY, ReleaseStage.FULL_RELEASE);
    }

    private static ProviderRelease stageless() {
        return release(STAGELESS, SWITCH_2, SWITCH_2_DAY, ReleaseStage.UNKNOWN);
    }

    private static ProviderWork work(String id, ProviderRelease... releases) {
        return new ProviderWork(
                id,
                "Game " + id,
                ProviderWorkType.MAIN_GAME,
                NOW,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(releases),
                List.of());
    }

    private static ProviderRelease release(
            String id, String platform, String day, ReleaseStage stage) {
        return new ProviderRelease(
                id,
                new ProviderPlatform(platform, "Platform " + platform, "platform-" + platform),
                Optional.of(new ProviderRegion("8", "worldwide")),
                new ReleaseDate.Day(LocalDate.parse(day)),
                ProviderReleaseSignal.NONE,
                stage);
    }

    /** Stored release stage by provider reference, for every Release of the catalogue. */
    private Map<String, String> storedStages() {
        Map<String, String> stages = new TreeMap<>();
        jdbc.query(
                "SELECT r.provider_id, s.release_stage FROM catalogue.release_snapshot s"
                        + " JOIN catalogue.release_external_reference r"
                        + " ON r.release_id = s.release_id",
                row -> {
                    stages.put(row.getString(1), row.getString(2));
                });
        return stages;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM catalogue." + table, Integer.class);
    }

    private String version() {
        return jdbc.queryForObject(
                "SELECT catalogue_version FROM catalogue.catalogue_publication", String.class);
    }

    /** Records each Game failure as stage/reason/identity state, in run order. */
    private record RecordingProgress(List<String> failures) implements SynchronizationProgress {

        @Override
        public Run started(
                UUID runId,
                CatalogueSynchronizationRequest request,
                Instant started,
                int pageSize) {
            return new Run() {
                @Override
                public void pageRequested(int page, Counters counters) {}

                @Override
                public void pageFetched(int page, int games, Counters counters) {}

                @Override
                public void pageLookupUnavailable(
                        int page, int games, Failure failure, Counters counters) {}

                @Override
                public void gameSucceeded(
                        int page, int position, GameResult result, Counters counters) {}

                @Override
                public void gameFailed(
                        int page,
                        int position,
                        Failure failure,
                        Optional<SynchronizedGameIdentity> game,
                        Counters counters) {
                    failures.add(
                            failure.stage()
                                    + "/"
                                    + failure.reason()
                                    + "/"
                                    + game.map(g -> g.published() ? "published" : "unpublished")
                                            .orElse("none"));
                }

                @Override
                public void pageCompleted(int page, Counters counters) {}

                @Override
                public void runFailed(Failure failure, Counters counters) {}

                @Override
                public void finished(CatalogueSynchronizationReport report) {}
            };
        }

        @Override
        public void skipped(CatalogueSynchronizationReport report) {}
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
        public LogoBatch logos(List<String> ids) {
            return new LogoBatch(Map.of(), ProviderCallStatistics.none());
        }
    }
}
