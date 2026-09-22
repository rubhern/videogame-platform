package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderCover;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWorkBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ReleasePage;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.domain.ReleaseDate;
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

class JdbcCatalogueSynchronizationStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");
    private static final CatalogueSynchronizationRequest WINDOW =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
    private JdbcTemplate jdbc;
    private JdbcCatalogueSynchronizationStore store;
    private FixtureProvider provider;
    private CatalogueSynchronizationService service;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("reconcile");
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
        provider = new FixtureProvider();
        service =
                new CatalogueSynchronizationService(
                        store,
                        provider,
                        Clock.fixed(NOW, ZoneId.of("Europe/Madrid")),
                        new SynchronizationPolicy(2, 25, 50, Duration.ofMinutes(30)),
                        new CoverSelectionPolicy(
                                "/assets/covers/fallback.svg", "VideoGame Platform"));
    }

    @Test
    void onePostTraversesEveryPageAndDeduplicatesGamesAcrossPages() {
        provider.rows =
                List.of(
                        new Row(10, "100"),
                        new Row(11, "100"),
                        new Row(12, "101"),
                        new Row(13, "100"),
                        new Row(14, "102"));
        provider.works.put(
                "100",
                work(
                        "100",
                        release("10", "2026-05-01"),
                        release("11", "2026-06-01"),
                        release("13", "2026-07-01")));
        provider.works.put("101", work("101", release("12", "2026-08-01")));
        provider.works.put("102", work("102", release("14", "2026-09-01")));

        var result = service.synchronize(WINDOW);

        assertThat(result.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(result.from()).isEqualTo(WINDOW.from());
        assertThat(result.to()).isEqualTo(WINDOW.to());
        assertThat(result.counters().inspectedReleaseDates()).isEqualTo(4);
        assertThat(result.counters().createdGames()).isEqualTo(3);
        assertThat(provider.pageLimits).containsOnly(2);
        assertThat(provider.fetched).containsExactly("100", "101", "102");
        assertThat(count("game")).isEqualTo(3);
        assertThat(count("release_snapshot")).isEqualTo(5);
        assertThat(store.lastRun()).contains(result);
    }

    @Test
    void repeatedWindowUpdatesKnownGamesAndReleasesWithoutDuplicating() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        service.synchronize(WINDOW);
        var before = store.loadGame("100", 25).orElseThrow();
        UUID releaseId = before.releases().get("10").releaseId();

        provider.works.put(
                "100",
                new ProviderWork(
                        "100",
                        "Renamed",
                        ProviderWorkType.MAIN_GAME,
                        NOW,
                        Optional.of(
                                new ProviderCover("new_cover", "https://www.igdb.com/games/game")),
                        List.of(release("10", "2026-10-01"), release("11", "2026-11-01")),
                        List.of()));
        var changed = service.synchronize(WINDOW);
        var after = store.loadGame("100", 25).orElseThrow();

        assertThat(changed.counters().updatedGames()).isEqualTo(1);
        assertThat(changed.counters().updatedReleases()).isEqualTo(1);
        assertThat(changed.counters().createdReleases()).isEqualTo(1);
        assertThat(after.gameId()).isEqualTo(before.gameId());
        assertThat(after.releases().get("10").releaseId()).isEqualTo(releaseId);
        assertThat(after.releases()).hasSize(2);
        String revision = version();
        var unchanged = service.synchronize(WINDOW);
        assertThat(unchanged.counters().unchangedGames()).isEqualTo(1);
        assertThat(version()).isEqualTo(revision);
        assertThat(count("game")).isEqualTo(1);
        assertThat(count("release_snapshot")).isEqualTo(2);
    }

    @Test
    void aKnownGameIsReconciledWheneverTheRequestedWindowFindsItAgain() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        service.synchronize(WINDOW);
        provider.rows = List.of(new Row(11, "100"));
        provider.works.put("100", work("100", release("10", "2028-05-01")));

        var result = service.synchronize(WINDOW);

        assertThat(result.counters().updatedGames()).isEqualTo(1);
        assertThat(store.loadGame("100", 25).orElseThrow().releases().get("10").date())
                .isEqualTo(new ReleaseDate.Day(LocalDate.parse("2028-05-01")));
    }

    @Test
    void aFailedGameDoesNotStopTheIntervalAndARetryIsIdempotent() {
        provider.rows = List.of(new Row(10, "100"), new Row(11, "101"), new Row(12, "102"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        provider.works.put("102", work("102", release("12", "2026-07-01")));

        var partial = service.synchronize(WINDOW);
        assertThat(partial.outcome()).isEqualTo(SynchronizationOutcome.PARTIAL);
        assertThat(count("game")).isEqualTo(2);

        provider.fetched.clear();
        provider.works.put("101", work("101", release("11", "2026-06-01")));
        var retried = service.synchronize(WINDOW);
        assertThat(retried.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(provider.fetched).containsExactly("100", "101", "102");
        assertThat(count("game")).isEqualTo(3);
    }

    @Test
    void aDifferentWindowStartsANewCompleteSynchronization() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        service.synchronize(WINDOW);
        provider.fetched.clear();
        provider.rows = List.of(new Row(1, "101"));
        provider.works.put("101", work("101", release("1", "2027-05-01")));
        var next =
                new CatalogueSynchronizationRequest(
                        LocalDate.parse("2027-01-01"), LocalDate.parse("2027-12-31"));

        var result = service.synchronize(next);

        assertThat(result.from()).isEqualTo(next.from());
        assertThat(result.to()).isEqualTo(next.to());
        assertThat(provider.fetched).contains("101");
    }

    @Test
    void invalidAggregateRollsBackWithoutAdvancingPastIt() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put(
                "100",
                new ProviderWork(
                        "100",
                        "x".repeat(301),
                        ProviderWorkType.MAIN_GAME,
                        NOW,
                        Optional.empty(),
                        List.of(release("10", "2026-05-01")),
                        List.of()));

        var result = service.synchronize(WINDOW);

        assertThat(result.outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(count("game")).isZero();
        assertThat(count("game_external_reference")).isZero();
    }

    @Test
    void oneGameCanOwnSeveralPlatformAndRegionReleasesFromTheNormalMigration() {
        provider.rows =
                List.of(
                        new Row(10, "100"),
                        new Row(11, "100"),
                        new Row(12, "100"),
                        new Row(13, "100"));
        var date = new ReleaseDate.Day(LocalDate.parse("2026-10-01"));
        provider.works.put(
                "100",
                work(
                        "100",
                        pr("10", "6", "8", date),
                        pr("11", "167", "1", date),
                        pr("12", "167", "2", date),
                        pr("13", "169", "8", date)));

        var result = service.synchronize(WINDOW);

        assertThat(result.counters().createdGames()).isEqualTo(1);
        assertThat(result.counters().createdReleases()).isEqualTo(4);
        assertThat(count("game")).isEqualTo(1);
        assertThat(count("release_snapshot")).isEqualTo(4);
    }

    @Test
    void acquiresUnknownTaxonomyByReferenceAndReusesItWithStableIdentity() {
        var date = new ReleaseDate.Day(LocalDate.parse("2026-05-01"));
        provider.rows = List.of(new Row(10, "100"), new Row(11, "100"));
        provider.works.put(
                "100",
                work(
                        "100",
                        pr("10", "167", "8", date),
                        acquired("11", "9999", "New Handheld", "new-handheld", "7777", "Nova", date)));

        assertThat(service.synchronize(WINDOW).outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);

        // A known provider reference reuses the backfilled seed identity instead of duplicating it.
        assertThat(platformIdFor("167"))
                .isEqualTo(UUID.fromString("10000000-0000-4000-8000-000000000001"));
        // An unknown reference creates the product taxonomy and its reference as accepted state.
        UUID acquiredPlatform = platformIdFor("9999");
        UUID acquiredRegion = regionIdFor("7777");
        assertThat(acquiredPlatform).isNotNull();
        assertThat(acquiredRegion).isNotNull();
        int platforms = count("platform");
        int regions = count("region");

        // A second run reuses the reference: no duplicate product taxonomy.
        service.synchronize(WINDOW);
        assertThat(platformIdFor("9999")).isEqualTo(acquiredPlatform);
        assertThat(regionIdFor("7777")).isEqualTo(acquiredRegion);
        assertThat(count("platform")).isEqualTo(platforms);
        assertThat(count("region")).isEqualTo(regions);

        // A provider slug/name change never changes or merges product identity.
        provider.works.put(
                "100",
                work(
                        "100",
                        pr("10", "167", "8", date),
                        acquired(
                                "11",
                                "9999",
                                "New Handheld Pro",
                                "new-handheld-pro",
                                "7777",
                                "Nova Renamed",
                                date)));
        service.synchronize(WINDOW);
        assertThat(platformIdFor("9999")).isEqualTo(acquiredPlatform);
        assertThat(regionIdFor("7777")).isEqualTo(acquiredRegion);
        assertThat(count("platform")).isEqualTo(platforms);
        assertThat(count("region")).isEqualTo(regions);
    }

    @Test
    void changedReleaseTupleKeepsIdentityAndDistinctReferencesAreNotMerged() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-10-01")));
        service.synchronize(WINDOW);
        UUID id = store.loadGame("100", 25).orElseThrow().releases().get("10").releaseId();
        var date = new ReleaseDate.Day(LocalDate.parse("2026-10-01"));
        var changed = pr("10", "167", "8", date);
        provider.works.put("100", work("100", changed));
        service.synchronize(WINDOW);
        assertThat(store.loadGame("100", 25).orElseThrow().releases().get("10").releaseId())
                .isEqualTo(id);

        provider.works.put("100", work("100", changed, pr("11", "167", "8", date)));
        assertThat(service.synchronize(WINDOW).outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(count("release_external_reference")).isEqualTo(1);
    }

    @Test
    void abandonedRunCannotWriteAfterAHeartbeatOwnedSuccessor() {
        Instant systemNow = Instant.now();
        UUID abandoned =
                store.beginRun(
                                "IGDB",
                                WINDOW,
                                systemNow.minus(Duration.ofHours(2)),
                                Duration.ofMinutes(30))
                        .orElseThrow();
        UUID successor =
                store.beginRun("IGDB", WINDOW, systemNow, Duration.ofMinutes(30)).orElseThrow();
        assertThat(successor).isNotEqualTo(abandoned);
        assertThatThrownBy(() -> store.heartbeat(abandoned))
                .isInstanceOf(SynchronizationWriteException.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM catalogue." + table, Integer.class);
    }

    private UUID platformIdFor(String providerId) {
        return jdbc
                .query(
                        "SELECT platform_id FROM catalogue.platform_external_reference"
                                + " WHERE provider='IGDB' AND provider_id=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        providerId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private UUID regionIdFor(String providerId) {
        return jdbc
                .query(
                        "SELECT region_id FROM catalogue.region_external_reference"
                                + " WHERE provider='IGDB' AND provider_id=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        providerId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static ProviderRelease acquired(
            String id,
            String platformRef,
            String platformName,
            String platformSlug,
            String regionRef,
            String regionName,
            ReleaseDate date) {
        return new ProviderRelease(
                id,
                new ProviderPlatform(platformRef, platformName, platformSlug),
                Optional.of(new ProviderRegion(regionRef, regionName)),
                date,
                ProviderReleaseSignal.NONE);
    }

    private String version() {
        return jdbc.queryForObject(
                "SELECT catalogue_version FROM catalogue.catalogue_publication", String.class);
    }

    private static ProviderRelease release(String id, String date) {
        return pr("6", id, date);
    }

    private static ProviderRelease pr(String platformRef, String id, String date) {
        return pr(id, platformRef, "8", new ReleaseDate.Day(LocalDate.parse(date)));
    }

    /** Provider taxonomy references; the store resolves them to product identity by reference. */
    private static ProviderRelease pr(
            String id, String platformRef, String regionRef, ReleaseDate date) {
        return new ProviderRelease(
                id,
                new ProviderPlatform(platformRef, "Platform " + platformRef, "platform-" + platformRef),
                Optional.of(new ProviderRegion(regionRef, "Region " + regionRef)),
                date,
                ProviderReleaseSignal.NONE);
    }

    private static ProviderWork work(String id, ProviderRelease... releases) {
        return new ProviderWork(
                id,
                "Game " + id,
                ProviderWorkType.MAIN_GAME,
                NOW,
                Optional.empty(),
                List.of(releases),
                List.of());
    }

    private record Row(long id, String game) {}

    private static final class FixtureProvider implements CatalogueProviderPort {
        List<Row> rows = List.of();
        Map<String, ProviderWork> works = new HashMap<>();
        List<Integer> pageLimits = new ArrayList<>();
        List<String> fetched = new ArrayList<>();

        public String providerName() {
            return "IGDB";
        }

        public boolean isConfigured() {
            return true;
        }

        public ReleasePage releaseGames(LocalDate from, LocalDate to, long afterGameId, int limit) {
            pageLimits.add(limit);
            List<Row> page =
                    rows.stream()
                            .filter(row -> Long.parseLong(row.game()) > afterGameId)
                            .sorted(
                                    java.util.Comparator.comparingLong(
                                                    (Row row) -> Long.parseLong(row.game()))
                                            .thenComparingLong(Row::id))
                            .limit(limit)
                            .toList();
            return new ReleasePage(
                    page.stream().map(Row::game).distinct().toList(),
                    page.size(),
                    page.isEmpty() ? afterGameId : Long.parseLong(page.getLast().game()),
                    page.size() < limit,
                    ProviderCallStatistics.none());
        }

        public ProviderWorkBatch fetchWorks(List<String> ids) {
            fetched.addAll(ids);
            return new ProviderWorkBatch(
                    ids.stream().map(works::get).filter(Objects::nonNull).toList(),
                    ProviderCallStatistics.none());
        }
    }
}
