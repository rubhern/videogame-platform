package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.operator.CatalogueSynchronizationEndpoint;
import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.internal.CatalogueSynchronizationService;
import com.videogameplatform.catalogue.application.synchronization.internal.CoverSelectionPolicy;
import com.videogameplatform.catalogue.application.synchronization.internal.SynchronizationPolicy;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.LogoBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderCover;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderImage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWorkBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ReleasePage;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
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
                                "/assets/covers/fallback.svg", "VideoGame Platform"),
                        SynchronizationProgress.NONE);
    }

    @Test
    void completeGameRepairClearsRepresentativeStaleReviewButPreservesUnknownAndIsIdempotent() {
        // Equivalent current evidence from the #151 investigation; no live provider or persistent
        // DB.
        provider.rows = List.of(new Row(10, "325602"), new Row(20, "282831"), new Row(30, "102"));
        provider.works.put(
                "325602",
                titledWork(
                        "325602",
                        "Onimusha: Way of the Sword",
                        fullRelease("10", "6", "2026-09-04"),
                        fullRelease("11", "167", "2026-09-04"),
                        fullRelease("12", "169", "2026-09-04")));
        provider.works.put(
                "282831",
                titledWork(
                        "282831",
                        "The Blood of Dawnwalker",
                        fullRelease("20", "6", "2026-09-03"),
                        fullRelease("21", "167", "2026-09-03"),
                        fullRelease("22", "169", "2026-09-03")));
        provider.works.put("102", work("102", pr("30", "6", "8", new ReleaseDate.Unknown())));
        assertThat(service.synchronize(WINDOW).outcome())
                .isEqualTo(SynchronizationOutcome.SUCCEEDED);
        jdbc.update("UPDATE catalogue.release_snapshot SET review_status='required'");
        var before =
                jdbc.queryForList("SELECT * FROM catalogue.release_snapshot ORDER BY release_id");
        var onimusha = store.loadGame("325602", 25).orElseThrow();
        var dawnwalker = store.loadGame("282831", 25).orElseThrow();
        var uncertain = store.loadGame("102", 25).orElseThrow();
        var repair =
                new com.videogameplatform.catalogue.application.synchronization.ReleaseStageRepair(
                        new JdbcReleaseStageRepairStore(
                                new NamedParameterJdbcTemplate(jdbc), "IGDB"),
                        service);
        var zero = new UUID(0, 0);
        String revision = version();
        int runs = count("synchronization_run");
        provider.fetched.clear();
        var preview = repair.repair(zero, 3, true);
        assertThat(preview.complete()).isTrue();
        assertThat(preview.reconciliation().counters().updatedReleases()).isEqualTo(6);
        assertThat(provider.fetched).containsExactlyInAnyOrder("325602", "282831", "102");
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM catalogue.release_snapshot ORDER BY release_id"))
                .isEqualTo(before);
        assertThat(version()).isEqualTo(revision);
        assertThat(count("synchronization_run")).isEqualTo(runs);

        var applied = repair.repair(zero, 3, false);
        assertThat(applied.complete()).isTrue();
        assertThat(applied.reconciliation().outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(applied.reconciliation().counters().updatedReleases()).isEqualTo(6);
        assertThat(applied.reconciliation().counters().deletedReleases()).isZero();
        for (var previous : List.of(onimusha, dawnwalker)) {
            var corrected =
                    store.loadGame(previous == onimusha ? "325602" : "282831", 25).orElseThrow();
            assertThat(corrected.gameId()).isEqualTo(previous.gameId());
            assertThat(corrected.releases()).hasSameSizeAs(previous.releases());
            corrected
                    .releases()
                    .forEach(
                            (reference, release) -> {
                                assertThat(release.releaseId())
                                        .isEqualTo(previous.releases().get(reference).releaseId());
                                assertThat(release.date())
                                        .isEqualTo(previous.releases().get(reference).date());
                                assertThat(release.stage()).isEqualTo(ReleaseStage.FULL_RELEASE);
                                assertThat(release.verificationLevel())
                                        .isEqualTo(VerificationLevel.PROVIDER_ONLY);
                                assertThat(release.reviewStatus())
                                        .isEqualTo(ReviewStatus.NOT_REQUIRED);
                            });
        }
        assertThat(store.loadGame("102", 25)).contains(uncertain);
        assertThat(version()).isNotEqualTo(revision);
        String correctedRevision = version();
        var correctedRows =
                jdbc.queryForList("SELECT * FROM catalogue.release_snapshot ORDER BY release_id");
        var repeat = repair.repair(zero, 3, false);
        assertThat(repeat.reconciliation().counters().updatedGames()).isZero();
        assertThat(repeat.reconciliation().counters().updatedReleases()).isZero();
        assertThat(repeat.reconciliation().counters().deletedReleases()).isZero();
        assertThat(version()).isEqualTo(correctedRevision);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM catalogue.release_snapshot ORDER BY release_id"))
                .isEqualTo(correctedRows);
    }

    @Test
    void normalSynchronizationRefinesUnknownDateAndClearsReviewInTheSameWrite() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", pr("10", "6", "8", new ReleaseDate.Unknown())));
        service.synchronize(WINDOW);
        var unknown = store.loadGame("100", 25).orElseThrow().releases().get("10");
        assertThat(unknown.reviewStatus()).isEqualTo(ReviewStatus.REQUIRED);
        provider.works.put("100", work("100", fullRelease("10", "6", "2026-09-04")));
        var corrected = service.synchronize(WINDOW);
        assertThat(corrected.counters().updatedReleases()).isEqualTo(1);
        var release = store.loadGame("100", 25).orElseThrow().releases().get("10");
        assertThat(release.releaseId()).isEqualTo(unknown.releaseId());
        assertThat(release.reviewStatus()).isEqualTo(ReviewStatus.NOT_REQUIRED);
        assertThat(release.date()).isEqualTo(new ReleaseDate.Day(LocalDate.parse("2026-09-04")));
        String revision = version();
        assertThat(service.synchronize(WINDOW).counters().updatedReleases()).isZero();
        assertThat(version()).isEqualTo(revision);
    }

    @Test
    void providerStageConflictRequiresReviewAndVerifiedConflictWithholdsTheWrite() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", fullRelease("10", "6", "2026-09-04")));
        service.synchronize(WINDOW);
        jdbc.update("UPDATE catalogue.release_snapshot SET release_stage='beta'");
        assertThat(service.synchronize(WINDOW).counters().updatedReleases()).isEqualTo(1);
        assertThat(store.loadGame("100", 25).orElseThrow().releases().get("10").reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
        jdbc.update(
                "UPDATE catalogue.release_snapshot SET release_stage='beta',verification_level='verified',last_verified_at=now()");
        var verified = jdbc.queryForList("SELECT * FROM catalogue.release_snapshot");
        String revision = version();
        assertThat(service.synchronize(WINDOW).counters().updatedReleases()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM catalogue.release_snapshot"))
                .isEqualTo(verified);
        assertThat(version()).isEqualTo(revision);
    }

    private static ProviderRelease fullRelease(String id, String platformRef, String date) {
        var r = pr(platformRef, id, date);
        return new ProviderRelease(
                r.providerId(),
                r.platform(),
                r.region(),
                r.date(),
                r.signal(),
                ReleaseStage.FULL_RELEASE);
    }

    private static ProviderWork titledWork(String id, String title, ProviderRelease... releases) {
        return new ProviderWork(
                id,
                title,
                ProviderWorkType.MAIN_GAME,
                NOW,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(releases),
                List.of());
    }

    @Test
    void readsOperationalReportsWrittenBeforeDeletionCountersExisted() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-10-02")));
        var result = service.synchronize(WINDOW);
        jdbc.update(
                "UPDATE catalogue.synchronization_run SET report=jsonb_set(report,'{counters}',(report->'counters')-'deletedReleases') WHERE run_id=?",
                result.runId());
        var old = store.lastRun().orElseThrow();
        assertThat(old.counters().deletedReleases()).isZero();
        assertThat(old.counters().createdReleases()).isEqualTo(1);
        jdbc.update(
                "UPDATE catalogue.synchronization_run SET report=jsonb_set(report,'{counters,createdGames}','null'::jsonb) WHERE run_id=?",
                result.runId());
        assertThatThrownBy(() -> store.lastRun())
                .isInstanceOf(tools.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    void replacementReferenceCanReuseTheObsoleteTupleWithoutMergingIdentity() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-10-02")));
        service.synchronize(WINDOW);
        var old = store.loadGame("100", 25).orElseThrow().releases().get("10").releaseId();
        provider.works.put("100", work("100", release("20", "2026-10-02")));
        var changed = service.synchronize(WINDOW);
        assertThat(changed.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(changed.counters().createdReleases()).isEqualTo(1);
        assertThat(changed.counters().deletedReleases()).isEqualTo(1);
        assertThat(store.loadGame("100", 25).orElseThrow().releases()).containsOnlyKeys("20");
        assertThat(store.loadGame("100", 25).orElseThrow().releases().get("20").releaseId())
                .isNotEqualTo(old);
    }

    @Test
    void returnedReferenceWithWithheldVerifiedUpdateIsStillPresent() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put(
                "100", work("100", release("10", "2026-05-01"), release("11", "2026-06-01")));
        service.synchronize(WINDOW);
        jdbc.update(
                "UPDATE catalogue.release_snapshot s SET verification_level='verified',last_verified_at=now() FROM catalogue.release_external_reference r WHERE r.release_id=s.release_id AND r.provider_id='10'");
        var previous = store.loadGame("100", 25).orElseThrow().releases().get("10");
        provider.works.put("100", work("100", release("10", "2026-10-02")));
        var changed = service.synchronize(WINDOW);
        assertThat(changed.counters().deletedReleases()).isEqualTo(1);
        assertThat(changed.counters().updatedReleases()).isZero();
        assertThat(store.loadGame("100", 25).orElseThrow().releases()).containsOnlyKeys("10");
        assertThat(store.loadGame("100", 25).orElseThrow().releases().get("10"))
                .isEqualTo(previous);
    }

    @Test
    void completeProviderSetRemovesApproximationCreatesFullAndKeepsStableReturnedIdentity() {
        provider.rows = List.of(new Row(10, "100"));
        var approximate = pr("10", "167", "8", new ReleaseDate.YearOnly(java.time.Year.of(2026)));
        provider.works.put("100", work("100", approximate, release("11", "2026-10-02")));
        service.synchronize(WINDOW);
        var previous = store.loadGame("100", 25).orElseThrow();
        var pcId = previous.releases().get("11").releaseId();
        var r = pr("20", "167", "8", new ReleaseDate.Day(LocalDate.parse("2026-10-02")));
        var full =
                new ProviderRelease(
                        r.providerId(),
                        r.platform(),
                        r.region(),
                        r.date(),
                        r.signal(),
                        com.videogameplatform.catalogue.domain.ReleaseStage.FULL_RELEASE);
        provider.works.put("100", work("100", full, release("11", "2026-10-02")));
        String before = version();
        var changed = service.synchronize(WINDOW);
        assertThat(changed.counters().deletedReleases()).isEqualTo(1);
        assertThat(changed.counters().createdReleases()).isEqualTo(1);
        var current = store.loadGame("100", 25).orElseThrow();
        assertThat(current.releases()).containsOnlyKeys("20", "11");
        assertThat(current.releases().get("11").releaseId()).isEqualTo(pcId);
        assertThat(current.releases().get("20").stage())
                .isEqualTo(com.videogameplatform.catalogue.domain.ReleaseStage.FULL_RELEASE);
        assertThat(count("game_release")).isEqualTo(2);
        assertThat(version()).isNotEqualTo(before);
        String after = version();
        var repeat = service.synchronize(WINDOW);
        assertThat(repeat.counters().deletedReleases()).isZero();
        assertThat(repeat.counters().updatedGames()).isZero();
        assertThat(version()).isEqualTo(after);
    }

    @Test
    void ownershipProtectsCuratedOfficialUnreferencedOtherProvidersAndSharedEvidence() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put(
                "100",
                work(
                        "100",
                        release("10", "2026-01-01"),
                        release("11", "2026-02-01"),
                        release("12", "2026-03-01"),
                        release("13", "2026-04-01"),
                        release("14", "2026-05-01"),
                        release("15", "2026-06-01")));
        service.synchronize(WINDOW);
        jdbc.update(
                "UPDATE catalogue.release_snapshot s SET source_kind='product_curated',source_name='Product',source_entity_type='curation' FROM catalogue.release_external_reference r WHERE s.release_id=r.release_id AND r.provider_id='10'");
        jdbc.update(
                "UPDATE catalogue.release_snapshot s SET source_kind='official_source',source_name='Publisher',source_entity_type='announcement' FROM catalogue.release_external_reference r WHERE s.release_id=r.release_id AND r.provider_id='11'");
        jdbc.update("DELETE FROM catalogue.release_external_reference WHERE provider_id='12'");
        jdbc.update(
                "UPDATE catalogue.release_external_reference SET provider='OTHER' WHERE provider_id='13'");
        jdbc.update(
                "INSERT INTO catalogue.release_external_reference(provider,provider_id,release_id,game_id) SELECT 'OTHER','shared',release_id,game_id FROM catalogue.release_external_reference WHERE provider_id='14'");
        var protectedRows =
                jdbc.queryForList(
                        "SELECT * FROM catalogue.release_snapshot WHERE exact_date<'2026-06-01' ORDER BY release_id");
        // A returned reference must not take ownership from curated evidence either.
        provider.works.put("100", work("100", release("10", "2027-01-01")));
        var changed = service.synchronize(WINDOW);
        assertThat(changed.counters().deletedReleases()).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM catalogue.release_snapshot ORDER BY release_id"))
                .isEqualTo(protectedRows);
        assertThat(count("game_release")).isEqualTo(5);
    }

    @Test
    void failuresMissingGameInvalidReleaseAndOverflowPreserveWholePreviousState() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        service.synchronize(WINDOW);
        var before = store.loadGame("100", 25).orElseThrow();
        String revision = version();
        provider.works.clear();
        assertThat(service.synchronize(WINDOW).outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        provider.failure =
                new com.videogameplatform.catalogue.application.synchronization.port
                        .ProviderRequestException(
                        com.videogameplatform.catalogue.application.synchronization.port
                                .ProviderFailureCode.PROVIDER_RESPONSE_INVALID,
                        ProviderCallStatistics.none());
        assertThat(service.synchronize(WINDOW).outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        provider.failure = null;
        provider.works.put(
                "100",
                new ProviderWork(
                        "100",
                        "Invalid rename",
                        ProviderWorkType.MAIN_GAME,
                        NOW,
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(release("20", "2026-10-02")),
                        List.of(
                                com.videogameplatform.catalogue.application.synchronization.port
                                        .ProviderMappingFailure.RELEASE_DATE_INVALID)));
        assertThat(service.synchronize(WINDOW).outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        var overflow =
                java.util.stream.IntStream.range(100, 126)
                        .mapToObj(i -> release(Integer.toString(i), "2026-10-02"))
                        .toArray(ProviderRelease[]::new);
        provider.works.put("100", work("100", overflow));
        assertThat(service.synchronize(WINDOW).outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(store.loadGame("100", 25)).contains(before);
        assertThat(version()).isEqualTo(revision);
    }

    @Test
    void failureAfterDeletionRollsBackRemovalCreationTitleAndRevisionButOtherGameSucceeds() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        service.synchronize(WINDOW);
        var before = store.loadGame("100", 25).orElseThrow();
        // Two returned references violate the durable product tuple constraint after old deletion.
        provider.rows = List.of(new Row(20, "100"), new Row(30, "101"));
        provider.works.put(
                "100",
                new ProviderWork(
                        "100",
                        "Failed rename",
                        ProviderWorkType.MAIN_GAME,
                        NOW,
                        Optional.of(
                                new ProviderCover("new_cover", "https://www.igdb.com/games/game")),
                        List.of(),
                        Optional.empty(),
                        List.of(release("20", "2026-10-02"), release("21", "2026-10-02")),
                        List.of()));
        provider.works.put("101", work("101", release("30", "2026-10-03")));
        var result = service.synchronize(WINDOW);
        assertThat(result.outcome()).isEqualTo(SynchronizationOutcome.PARTIAL);
        assertThat(result.counters().deletedReleases()).isZero();
        assertThat(store.loadGame("100", 25)).contains(before);
        assertThat(store.loadGame("101", 25)).isPresent();
        assertThat(count("game_release")).isEqualTo(2);
    }

    @Test
    void featuredEvidenceIsAtomicIdempotentAndDistinguishesMissingFromInvalid() {
        provider.rows = List.of(new Row(10, "100"));
        ProviderWork original = work("100", release("10", "2026-10-02"));
        provider.works.put(
                "100",
                withEvidence(
                        original,
                        Optional.of(292L),
                        Optional.of(LocalDate.of(2026, 10, 2)),
                        false));
        service.synchronize(WINDOW);
        assertThat(popularity("100"))
                .containsEntry("hypes", 292L)
                .containsEntry("eligible_product", true);
        String revision = version();
        serviceAt(NOW.plusSeconds(1)).synchronize(WINDOW);
        assertThat(version()).isEqualTo(revision);
        assertThat(popularity("100")).containsEntry("observed_at", NOW.plusSeconds(1));
        // A mapping failure keeps complete last-valid evidence.
        provider.works.put("100", original);
        serviceAt(NOW.plusSeconds(2)).synchronize(WINDOW);
        assertThat(popularity("100"))
                .containsEntry("hypes", 292L)
                .containsEntry("observed_at", NOW.plusSeconds(1));
        // A valid answer with no Hypes or first-release evidence removes eligibility without
        // deleting the Game.
        provider.works.put("100", withEvidence(original, Optional.empty(), Optional.empty(), true));
        serviceAt(NOW.plusSeconds(3)).synchronize(WINDOW);
        assertThat(popularity("100"))
                .containsEntry("hypes", null)
                .containsEntry("first_release_date", null)
                .containsEntry("eligible_product", false);
        assertThat(count("game")).isEqualTo(1);
        assertThat(count("game_release")).isEqualTo(1);
        assertThat(version()).isEqualTo(revision);
    }

    @Test
    void failedGameWriteKeepsLastValidFeaturedEvidence() {
        provider.rows = List.of(new Row(10, "100"));
        ProviderWork original = work("100", release("10", "2026-10-02"));
        provider.works.put(
                "100",
                withEvidence(
                        original,
                        Optional.of(292L),
                        Optional.of(LocalDate.of(2026, 10, 2)),
                        false));
        service.synchronize(WINDOW);
        // Duplicate release references invalidate the complete aggregate before it can publish new
        // evidence.
        provider.works.put(
                "100",
                withEvidence(
                        work("100", release("10", "2026-10-02"), release("10", "2026-10-02")),
                        Optional.of(999L),
                        Optional.of(LocalDate.of(2026, 10, 2)),
                        false));
        serviceAt(NOW.plusSeconds(1)).synchronize(WINDOW);
        assertThat(popularity("100"))
                .containsEntry("hypes", 292L)
                .containsEntry("observed_at", NOW);
    }

    private static ProviderWork withEvidence(
            ProviderWork w, Optional<Long> hypes, Optional<LocalDate> first, boolean edition) {
        return new ProviderWork(
                w.providerId(),
                w.title(),
                w.type(),
                w.providerUpdatedAt(),
                w.cover(),
                w.images(),
                w.attributionUrl(),
                w.releases(),
                w.mappingFailures(),
                Optional.of(
                        new CatalogueProviderPort.ProviderFeaturedEvidence(first, edition, hypes)));
    }

    @Test
    void featuredMediaStillRefreshAndKeepTheirLastValidSelectionWithoutARevision() {
        provider.rows = List.of(new Row(10, "100"));
        var artwork = image(ImageKind.ARTWORK, "artest", 1920, 1080, false);
        var logo = image(ImageKind.LOGO, "lotest", 900, 300, true);
        provider.works.put("100", mediaWork("100", List.of(artwork), release("10", "2026-10-02")));
        provider.logos.put("100", List.of(logo));
        var first = service.synchronize(WINDOW);
        assertThat(first.counters().featuredImageObservedGames()).isEqualTo(1);
        assertThat(first.counters().logoObservedGames()).isEqualTo(1);
        assertThat(media("100", "image")).containsEntry("image_reference", "artest");
        assertThat(media("100", "logo")).containsEntry("image_reference", "lotest");
        assertThat(media("100", "card_image")).containsEntry("image_reference", "artest");
        String revision = version();
        provider.works.put("100", mediaWork("100", List.of(), release("10", "2026-10-02")));
        provider.logos.clear();
        var savedCardImage = media("100", "card_image");
        serviceAt(NOW.plusSeconds(1)).synchronize(WINDOW);
        assertThat(media("100", "image"))
                .containsEntry("image_reference", "artest")
                .containsEntry("observed_at", NOW);
        assertThat(media("100", "card_image")).isEqualTo(savedCardImage);
        assertThat(media("100", "logo"))
                .containsEntry("image_reference", "lotest")
                .containsEntry("observed_at", NOW);
        assertThat(version()).isEqualTo(revision);
    }

    @Test
    void theDatabaseRejectsFeaturedMediaOutsideTheApprovedReferenceShape() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-10-02")));
        service.synchronize(WINDOW);
        String game =
                jdbc.queryForObject(
                        "SELECT game_id::text FROM catalogue.game_external_reference"
                                + " WHERE provider_id='100'",
                        String.class);
        String insert =
                "INSERT INTO catalogue.game_featured_media(game_id, media_role, media_kind,"
                        + " image_reference, width, height, transparent, source_name, source_url,"
                        + " observed_at) VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?, false, ?, ?, now())";
        for (Object[] invalid :
                List.of(
                        new Object[] {"image", "logo", "ar", 10, 10, "IGDB", ATTRIBUTION},
                        new Object[] {"logo", "artwork", "lo", 10, 10, "IGDB", ATTRIBUTION},
                        new Object[] {"image", "artwork", "../ar", 10, 10, "IGDB", ATTRIBUTION},
                        new Object[] {"image", "artwork", "ar", 0, 10, "IGDB", ATTRIBUTION},
                        new Object[] {"image", "artwork", "ar", 10, 10, "Other", ATTRIBUTION},
                        new Object[] {
                            "image", "artwork", "ar", 10, 10, "IGDB", "https://example.com/games/x"
                        })) {
            Object[] arguments = new Object[invalid.length + 1];
            arguments[0] = game;
            System.arraycopy(invalid, 0, arguments, 1, invalid.length);
            assertThatThrownBy(() -> jdbc.update(insert, arguments))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        jdbc.update(insert, game, "image", "artwork", "ar", 10, 10, "IGDB", ATTRIBUTION);
        // One selection per Game and role.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        game,
                                        "image",
                                        "screenshot",
                                        "sc",
                                        10,
                                        10,
                                        "IGDB",
                                        ATTRIBUTION))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsAnInventedOrUnattributedPopularitySignal() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-10-02")));
        service.synchronize(WINDOW);
        String game =
                jdbc.queryForObject(
                        "SELECT game_id::text FROM catalogue.game_external_reference"
                                + " WHERE provider_id='100'",
                        String.class);
        for (String invalid :
                List.of(
                        "INSERT INTO catalogue.game_featured_evidence (game_id,hypes,source_name,first_release_date,eligible_product,observed_at) VALUES (CAST(? AS uuid), 0,"
                                + " 'IGDB', NULL, true, now())",
                        "INSERT INTO catalogue.game_featured_evidence (game_id,hypes,source_name,first_release_date,eligible_product,observed_at) VALUES (CAST(? AS uuid), 1,"
                                + " ' ', NULL, true, now())")) {
            assertThatThrownBy(() -> jdbc.update(invalid, game))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO catalogue.game_featured_evidence VALUES"
                                                + " (gen_random_uuid(), 1, NULL, true, 'IGDB', now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void aValidEmptyCompleteSetRemovesOnlyOwnedReleasesAndPreservesTheGame() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        service.synchronize(WINDOW);
        provider.works.put("100", work("100"));
        var result = service.synchronize(WINDOW);
        assertThat(result.counters().deletedReleases()).isEqualTo(1);
        assertThat(count("game_release")).isZero();
        assertThat(count("game")).isEqualTo(1);
        assertThat(service.synchronize(WINDOW).counters().deletedReleases()).isZero();
    }

    @Test
    void completeGameBackfillIsPreviewableResumableAndIdempotentIncludingKnownStages() {
        provider.rows = List.of(new Row(10, "100"), new Row(11, "101"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        provider.works.put("101", work("101", release("11", "2026-06-01")));
        service.synchronize(WINDOW);
        jdbc.update("UPDATE catalogue.release_snapshot SET release_stage='full_release'");
        var repairStore =
                new JdbcReleaseStageRepairStore(new NamedParameterJdbcTemplate(jdbc), "IGDB");
        var repair =
                new com.videogameplatform.catalogue.application.synchronization.ReleaseStageRepair(
                        repairStore, service);
        var zero = new UUID(0, 0);
        var page = repairStore.knownGamesAfter(zero, 1);
        String first = page.getFirst().gameReference();
        provider.works.put(first, work(first, release("99", "2026-10-02")));
        String before = version();
        int runs = count("synchronization_run");
        var dry = repair.repair(zero, 1, true);
        assertThat(dry.reconciliation().counters().deletedReleases()).isEqualTo(1);
        assertThat(dry.complete()).isFalse();
        assertThat(version()).isEqualTo(before);
        assertThat(count("synchronization_run")).isEqualTo(runs);
        assertThat(count("game_release")).isEqualTo(2);
        assertThat(store.loadGame(first, 25).orElseThrow().releases()).doesNotContainKey("99");
        var applied = repair.repair(zero, 1, false);
        assertThat(applied.reconciliation().counters().deletedReleases()).isEqualTo(1);
        assertThat(store.loadGame(first, 25).orElseThrow().releases()).containsOnlyKeys("99");
        var next = repair.repair(UUID.fromString(applied.nextAfter()), 1, false);
        assertThat(next.complete()).isTrue();
        String after = version();
        var repeat = repair.repair(zero, 100, false);
        assertThat(repeat.reconciliation().counters().deletedReleases()).isZero();
        assertThat(repeat.reconciliation().counters().updatedGames()).isZero();
        assertThat(version()).isEqualTo(after);
        provider.works.remove(first);
        var failed = repair.repair(zero, 1, false);
        assertThat(failed.reconciliation().outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(failed.nextAfter()).isEqualTo(zero.toString());
        assertThat(failed.complete()).isFalse();
        assertThat(store.loadGame(first, 25).orElseThrow().releases()).containsOnlyKeys("99");
    }

    @Test
    void
            repeatedSynchronizationEnrichesUnknownStagesWithoutChangingReleaseIdentityOrEligibilityEvidence() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-05-01")));
        assertThat(service.synchronize(WINDOW).outcome())
                .isEqualTo(SynchronizationOutcome.SUCCEEDED);
        var before =
                jdbc.queryForMap(
                        "SELECT release_id,review_status,verification_level FROM catalogue.release_snapshot");
        var r = release("10", "2026-05-01");
        var full =
                new ProviderRelease(
                        r.providerId(),
                        r.platform(),
                        r.region(),
                        r.date(),
                        r.signal(),
                        com.videogameplatform.catalogue.domain.ReleaseStage.FULL_RELEASE);
        provider.works.put("100", work("100", full));
        var enriched = service.synchronize(WINDOW);
        assertThat(enriched.counters().updatedReleases()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT release_stage FROM catalogue.release_snapshot",
                                String.class))
                .isEqualTo("full_release");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT release_id,review_status,verification_level FROM catalogue.release_snapshot"))
                .isEqualTo(before);
        assertThat(service.synchronize(WINDOW).counters().updatedReleases()).isZero();
        provider.works.put("100", work("100", r));
        assertThat(service.synchronize(WINDOW).counters().updatedReleases()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT release_stage FROM catalogue.release_snapshot",
                                String.class))
                .isEqualTo("full_release");
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
                        List.of(),
                        Optional.empty(),
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
                        List.of(),
                        Optional.empty(),
                        List.of(release("10", "2026-05-01")),
                        List.of()));

        var run = org.mockito.Mockito.mock(SynchronizationProgress.Run.class);
        var progress = org.mockito.Mockito.mock(SynchronizationProgress.class);
        org.mockito.Mockito.when(
                        progress.started(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(run);
        var observed =
                new CatalogueSynchronizationService(
                        store,
                        provider,
                        Clock.fixed(NOW, ZoneId.of("Europe/Madrid")),
                        new SynchronizationPolicy(2, 25, 50, Duration.ofMinutes(30)),
                        new CoverSelectionPolicy(
                                "/assets/covers/fallback.svg", "VideoGame Platform"),
                        progress);

        var result = observed.synchronize(WINDOW);

        assertThat(result.outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(count("game")).isZero();
        assertThat(count("game_external_reference")).isZero();
        // PostgreSQL rejects the value; the operator sees a stable reason and the identity the
        // failed attempt assigned, never the driver message or the provider reference.
        var failure = org.mockito.ArgumentCaptor.forClass(SynchronizationProgress.Failure.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Optional<SynchronizedGameIdentity>> identity =
                org.mockito.ArgumentCaptor.forClass(Optional.class);
        org.mockito.Mockito.verify(run)
                .gameFailed(
                        org.mockito.ArgumentMatchers.eq(1),
                        org.mockito.ArgumentMatchers.eq(1),
                        failure.capture(),
                        identity.capture(),
                        org.mockito.ArgumentMatchers.any());
        assertThat(failure.getValue().reason())
                .isEqualTo(
                        SynchronizationWriteException.Reason.PERSISTENCE_CONSTRAINT_VIOLATION
                                .name());
        assertThat(identity.getValue())
                .hasValueSatisfying(
                        assigned -> {
                            assertThat(assigned.published()).isFalse();
                            assertThat(assigned.slug()).endsWith("-" + assigned.gameId());
                            assertThat(assigned.slug()).startsWith("xxx");
                        });
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
                        acquired(
                                "11",
                                "9999",
                                "New Handheld",
                                "new-handheld",
                                "7777",
                                "Nova",
                                date)));

        assertThat(service.synchronize(WINDOW).outcome())
                .isEqualTo(SynchronizationOutcome.SUCCEEDED);

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
    void acquiredRegionsReadAsProductLabelsThatAProviderRenameNeverChanges() {
        var date = new ReleaseDate.Day(LocalDate.parse("2026-05-01"));
        provider.rows =
                List.of(
                        new Row(10, "100"),
                        new Row(11, "100"),
                        new Row(12, "100"),
                        new Row(13, "100"));
        provider.works.put(
                "100",
                work(
                        "100",
                        inRegion("10", "8", "worldwide", date),
                        inRegion("11", "4", "new_zealand", date),
                        inRegion("12", "7", "asia", date),
                        inRegion("13", "7777", "middle_east", date)));

        assertThat(service.synchronize(WINDOW).outcome())
                .isEqualTo(SynchronizationOutcome.SUCCEEDED);

        // The seeded Worldwide identity is reused and reads its product label, not the descriptor.
        assertThat(regionIdFor("8"))
                .isEqualTo(UUID.fromString("20000000-0000-4000-8000-000000000001"));
        assertThat(regionLabel("8")).isEqualTo("Mundial");
        // Newly acquired regions: an approved Spanish label, a lowercase descriptor made readable,
        // and an unmapped descriptor degraded to readable words instead of a technical token.
        assertThat(regionLabel("4")).isEqualTo("Nueva Zelanda");
        assertThat(regionLabel("7")).isEqualTo("Asia");
        assertThat(regionLabel("7777")).isEqualTo("Middle East");
        UUID newZealand = regionIdFor("4");
        int regions = count("region");

        // A provider rename reuses the product region and leaves its product label alone.
        provider.works.put(
                "100",
                work(
                        "100",
                        inRegion("10", "8", "worldwide", date),
                        inRegion("11", "4", "aotearoa_new_zealand", date),
                        inRegion("12", "7", "asia", date),
                        inRegion("13", "7777", "middle_east", date)));
        assertThat(service.synchronize(WINDOW).outcome())
                .isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(regionIdFor("4")).isEqualTo(newZealand);
        assertThat(regionLabel("4")).isEqualTo("Nueva Zelanda");
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
    void overlappingScheduledWindowsReuseStableIdentitiesAndDoNotRotateAnUnchangedRevision() {
        provider.rows = List.of(new Row(10, "100"));
        provider.works.put("100", work("100", release("10", "2026-09-10")));
        var registry = new SimpleMeterRegistry();
        var tasks = scheduledTasks(new CatalogueSynchronizationMetrics(registry));
        tasks.getTriggerTaskList().getFirst().getRunnable().run();
        var before = store.loadGame("100", 25).orElseThrow();
        String revision = version();
        tasks.getTriggerTaskList().getLast().getRunnable().run();
        var after = store.loadGame("100", 25).orElseThrow();
        assertThat(after.gameId()).isEqualTo(before.gameId());
        assertThat(after.releases().get("10").releaseId())
                .isEqualTo(before.releases().get("10").releaseId());
        assertThat(version()).isEqualTo(revision);
        assertThat(count("game")).isEqualTo(1);
        assertThat(count("release_snapshot")).isEqualTo(1);
        assertThat(provider.fetched).containsExactly("100", "100");
        assertThat(
                        registry.get("catalogue.synchronization.run")
                                .tag("outcome", "succeeded")
                                .counter()
                                .count())
                .isEqualTo(2);
    }

    @Test
    void activeDatabaseRunSkipsBothScheduledPoliciesAndManualCommandWithoutProviderCalls() {
        UUID active = store.beginRun("IGDB", WINDOW, NOW, Duration.ofMinutes(30)).orElseThrow();
        var registry = new SimpleMeterRegistry();
        var metrics = new CatalogueSynchronizationMetrics(registry);
        scheduledTasks(metrics).getTriggerTaskList().forEach(task -> task.getRunnable().run());
        var manual =
                (CatalogueSynchronizationReport)
                        new CatalogueSynchronizationEndpoint(service, metrics)
                                .synchronize(WINDOW.from().toString(), WINDOW.to().toString());
        assertThat(manual.outcome()).isEqualTo(SynchronizationOutcome.SKIPPED);
        assertThat(manual.outcomeCode()).isEqualTo("SYNCHRONIZATION_ALREADY_RUNNING");
        assertThat(store.lastRun().orElseThrow().runId()).isEqualTo(active);
        assertThat(count("synchronization_run")).isEqualTo(1);
        assertThat(provider.pageLimits).isEmpty();
        assertThat(provider.fetched).isEmpty();
        assertThat(
                        registry.get("catalogue.synchronization.run")
                                .tag("outcome", "skipped")
                                .counter()
                                .count())
                .isEqualTo(3);
        for (String policy : List.of("near_term", "upcoming", "none")) {
            assertThat(
                            registry.get("catalogue.synchronization.trigger")
                                    .tags("policy", policy, "outcome", "skipped")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        }
    }

    private ScheduledTaskRegistrar scheduledTasks(CatalogueSynchronizationMetrics metrics) {
        var registrar = new ScheduledTaskRegistrar();
        new CatalogueSynchronizationScheduler(
                        service,
                        metrics,
                        Clock.fixed(NOW, ZoneId.of("Europe/Madrid")),
                        org.mockito.Mockito.mock(TaskScheduler.class),
                        new CatalogueSynchronizationScheduler.Policy("0 0 4 * * *", 28, 28),
                        new CatalogueSynchronizationScheduler.Policy("0 0 5 * * SUN", 0, 180))
                .configureTasks(registrar);
        return registrar;
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

    private String regionLabel(String providerId) {
        return jdbc.queryForObject(
                "SELECT display_name FROM catalogue.region WHERE region_id=?",
                String.class,
                regionIdFor(providerId));
    }

    /** A PlayStation 5 release in the provider region, described as the real IGDB catalogue does. */
    private static ProviderRelease inRegion(
            String id, String regionRef, String regionDescriptor, ReleaseDate date) {
        return new ProviderRelease(
                id,
                new ProviderPlatform("167", "PlayStation 5", "ps5"),
                Optional.of(new ProviderRegion(regionRef, regionDescriptor)),
                date,
                ProviderReleaseSignal.NONE,
                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN);
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
                ProviderReleaseSignal.NONE,
                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN);
    }

    private String version() {
        return jdbc.queryForObject(
                "SELECT catalogue_version FROM catalogue.catalogue_publication", String.class);
    }

    private CatalogueSynchronizationService serviceAt(Instant instant) {
        return new CatalogueSynchronizationService(
                store,
                provider,
                Clock.fixed(instant, ZoneId.of("Europe/Madrid")),
                new SynchronizationPolicy(2, 25, 50, Duration.ofMinutes(30)),
                new CoverSelectionPolicy("/assets/covers/fallback.svg", "VideoGame Platform"),
                SynchronizationProgress.NONE);
    }

    private static final String ATTRIBUTION = "https://www.igdb.com/games/featured-game";

    private static ProviderWork mediaWork(
            String id, List<ProviderImage> images, ProviderRelease... releases) {
        return new ProviderWork(
                id,
                "Game " + id,
                ProviderWorkType.MAIN_GAME,
                NOW,
                Optional.empty(),
                images,
                Optional.of(ATTRIBUTION),
                List.of(releases),
                List.of());
    }

    private static ProviderImage image(
            ImageKind kind, String reference, int width, int height, boolean transparent) {
        return new ProviderImage(kind, reference, width, height, transparent, false);
    }

    /** The stored featured media of one role for a provider Game, timestamps as instants. */
    private Map<String, Object> media(String providerGameId, String role) {
        Map<String, Object> row =
                new HashMap<>(
                        jdbc.queryForMap(
                                "SELECT m.media_kind, m.image_reference, m.width, m.height,"
                                        + " m.transparent, m.source_name, m.source_url,"
                                        + " m.observed_at FROM catalogue.game_featured_media m"
                                        + " JOIN catalogue.game_external_reference r"
                                        + " ON r.game_id = m.game_id"
                                        + " WHERE r.provider_id = ? AND m.media_role = ?",
                                providerGameId,
                                role));
        row.replaceAll(
                (column, value) ->
                        value instanceof java.sql.Timestamp timestamp
                                ? timestamp.toInstant()
                                : value);
        return row;
    }

    /** The stored signal of a provider Game, with timestamps as instants. */
    private Map<String, Object> popularity(String providerGameId) {
        Map<String, Object> row =
                new HashMap<>(
                        jdbc.queryForMap(
                                "SELECT p.hypes, p.source_name, p.first_release_date, p.eligible_product,"
                                        + " p.observed_at FROM catalogue.game_featured_evidence p"
                                        + " JOIN catalogue.game_external_reference r"
                                        + " ON r.game_id = p.game_id WHERE r.provider_id = ?",
                                providerGameId));
        row.replaceAll(
                (column, value) ->
                        value instanceof java.sql.Timestamp timestamp
                                ? timestamp.toInstant()
                                : value);
        return row;
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
                new ProviderPlatform(
                        platformRef, "Platform " + platformRef, "platform-" + platformRef),
                Optional.of(new ProviderRegion(regionRef, "Region " + regionRef)),
                date,
                ProviderReleaseSignal.NONE,
                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN);
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

    private record Row(long id, String game) {}

    private static final class FixtureProvider implements CatalogueProviderPort {
        com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException
                failure;
        List<Row> rows = List.of();
        Map<String, ProviderWork> works = new HashMap<>();
        List<Integer> pageLimits = new ArrayList<>();
        List<String> fetched = new ArrayList<>();
        Map<String, List<ProviderImage>> logos = new HashMap<>();
        com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException
                logoFailure;

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

        public LogoBatch logos(List<String> ids) {
            if (logoFailure != null) {
                throw logoFailure;
            }
            Map<String, List<ProviderImage>> answer = new HashMap<>();
            ids.stream().filter(logos::containsKey).forEach(id -> answer.put(id, logos.get(id)));
            return new LogoBatch(answer, ProviderCallStatistics.none());
        }
    }
}
