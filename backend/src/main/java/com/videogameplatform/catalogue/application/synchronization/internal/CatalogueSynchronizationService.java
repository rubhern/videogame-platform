package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWorkBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ReleasePage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CatalogueContext;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CoverSelection;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameState;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.ReleaseWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.WriteResult;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.domain.CatalogueSlug;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Synchronizes the complete requested date interval with bounded, in-call provider paging. */
public final class CatalogueSynchronizationService implements SynchronizeCatalogueUseCase {
    private final CatalogueSynchronizationStore store;
    private final CatalogueProviderPort provider;
    private final Clock clock;
    private final SynchronizationPolicy policy;
    private final CoverSelectionPolicy covers;

    public CatalogueSynchronizationService(
            CatalogueSynchronizationStore store,
            CatalogueProviderPort provider,
            Clock clock,
            SynchronizationPolicy policy,
            CoverSelectionPolicy covers) {
        this.store = store;
        this.provider = provider;
        this.clock = clock;
        this.policy = policy;
        this.covers = covers;
    }

    @Override
    public CatalogueSynchronizationReport synchronize(CatalogueSynchronizationRequest request) {
        Instant started = clock.instant();
        Counts counts = new Counts();
        if (!provider.isConfigured()) {
            return report(
                    null,
                    request,
                    started,
                    SynchronizationOutcome.SKIPPED,
                    "SYNCHRONIZATION_DISABLED",
                    counts);
        }
        Optional<UUID> acquired =
                store.beginRun(provider.providerName(), request, started, policy.abandonRunAfter());
        if (acquired.isEmpty()) {
            return report(
                    null,
                    request,
                    started,
                    SynchronizationOutcome.SKIPPED,
                    "SYNCHRONIZATION_ALREADY_RUNNING",
                    counts);
        }
        UUID runId = acquired.orElseThrow();
        long afterGameId = 0;
        try {
            CatalogueContext context = store.loadContext();
            while (true) {
                final ReleasePage page;
                try {
                    page =
                            provider.releaseGames(
                                    request.from(),
                                    request.to(),
                                    afterGameId,
                                    policy.providerPageSize());
                    counts.statistics = counts.statistics.plus(page.statistics());
                    counts.inspected += page.inspected();
                } catch (ProviderRequestException failure) {
                    counts.statistics = counts.statistics.plus(failure.statistics());
                    counts.failed++;
                    break;
                }
                for (String providerId : page.gameIds()) {
                    synchronizeGame(runId, providerId, context, started, counts);
                }
                store.heartbeat(runId);
                afterGameId = page.nextGameId();
                if (page.completed()) {
                    break;
                }
            }
            SynchronizationOutcome outcome = outcome(counts);
            CatalogueSynchronizationReport result =
                    report(
                            runId,
                            request,
                            started,
                            outcome,
                            outcome == SynchronizationOutcome.SUCCEEDED
                                    ? "SYNCHRONIZATION_COMPLETED"
                                    : outcome == SynchronizationOutcome.PARTIAL
                                            ? "SYNCHRONIZATION_COMPLETED_WITH_FAILURES"
                                            : "SYNCHRONIZATION_FAILED",
                            counts);
            store.completeRun(result, policy.retainedRuns());
            return result;
        } catch (RuntimeException failure) {
            counts.failed++;
            CatalogueSynchronizationReport result =
                    report(
                            runId,
                            request,
                            started,
                            successfulGames(counts) > 0
                                    ? SynchronizationOutcome.PARTIAL
                                    : SynchronizationOutcome.FAILED,
                            "SYNCHRONIZATION_FAILED",
                            counts);
            store.completeRun(result, policy.retainedRuns());
            if (!(failure instanceof ProviderRequestException
                    || failure instanceof SynchronizationWriteException)) {
                throw failure;
            }
            return result;
        }
    }

    private void synchronizeGame(
            UUID runId,
            String providerId,
            CatalogueContext context,
            Instant started,
            Counts counts) {
        try {
            ProviderWorkBatch workBatch = provider.fetchWorks(List.of(providerId));
            counts.statistics = counts.statistics.plus(workBatch.statistics());
            if (workBatch.works().size() != 1
                    || !providerId.equals(workBatch.works().getFirst().providerId())) {
                counts.failed++;
                return;
            }
            reconcile(runId, workBatch.works().getFirst(), context, started, counts);
        } catch (ProviderRequestException failure) {
            counts.statistics = counts.statistics.plus(failure.statistics());
            counts.failed++;
        } catch (SynchronizationWriteException | IllegalArgumentException failure) {
            counts.failed++;
        }
    }

    private static SynchronizationOutcome outcome(Counts counts) {
        if (counts.failed == 0) {
            return SynchronizationOutcome.SUCCEEDED;
        }
        return successfulGames(counts) == 0
                ? SynchronizationOutcome.FAILED
                : SynchronizationOutcome.PARTIAL;
    }

    private static long successfulGames(Counts counts) {
        return counts.created + counts.updated + counts.unchanged + counts.deferred;
    }

    private void reconcile(
            UUID runId,
            ProviderWork work,
            CatalogueContext context,
            Instant synchronizedAt,
            Counts counts) {
        Optional<GameState> existing =
                store.loadGame(work.providerId(), policy.maxReleasesPerGame());
        if (existing.isEmpty() && !GameImportPolicy.evaluate(work).accepted()) {
            counts.deferred++;
            return;
        }
        if (work.title() == null
                || work.title().isBlank()
                || work.releases().size() > policy.maxReleasesPerGame()
                || work.mappingFailures().stream()
                        .anyMatch(
                                failure ->
                                        failure == ProviderMappingFailure.RELEASE_DATE_INVALID
                                                || failure
                                                        == ProviderMappingFailure.RECORD_UNREADABLE
                                                || failure
                                                        == ProviderMappingFailure
                                                                .DUPLICATE_RELEASE_TUPLE)) {
            counts.failed++;
            return;
        }
        UUID gameId = existing.map(GameState::gameId).orElseGet(UUID::randomUUID);
        String slug =
                existing.map(GameState::slug)
                        .orElseGet(
                                () ->
                                        CatalogueSlug.fromTitle(work.title())
                                                .distinguishedBy(gameId.toString())
                                                .value());
        CoverSelection cover =
                existing.map(
                                game ->
                                        covers.forReconciliation(work.title(), work.cover())
                                                .orElse(game.cover()))
                        .orElseGet(() -> covers.forImport(work.title(), work.cover()));
        List<ReleaseWrite> releases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ProviderRelease providerRelease : work.releases()) {
            if (!seen.add(providerRelease.providerId())) {
                throw new IllegalArgumentException("A release needs a unique provider reference");
            }
            PublishedRelease previous =
                    existing.map(GameState::releases)
                            .map(values -> values.get(providerRelease.providerId()))
                            .orElse(null);
            Optional<PlannedRelease> release =
                    ReleaseReconciliationPolicy.reconcile(
                            gameId,
                            providerRelease,
                            previous,
                            context.platformIdsByCode(),
                            context.regionIdsByCode(),
                            LocalDate.now(clock),
                            synchronizedAt,
                            work.providerUpdatedAt(),
                            provider.providerName());
            release.ifPresent(
                    value -> releases.add(new ReleaseWrite(providerRelease.providerId(), value)));
            if (release.isEmpty()) {
                counts.unchangedReleases++;
            }
        }
        WriteResult result =
                store.saveGame(
                        runId,
                        new GameWrite(
                                work.providerId(),
                                gameId,
                                existing.isEmpty(),
                                work.title(),
                                slug,
                                cover,
                                releases,
                                synchronizedAt));
        if (result.createdGame()) {
            counts.created++;
        } else if (result.updatedGame()) {
            counts.updated++;
        } else {
            counts.unchanged++;
        }
        counts.createdReleases += result.createdReleases();
        counts.updatedReleases += result.updatedReleases();
        counts.unchangedReleases += result.unchangedReleases();
    }

    @Override
    public Optional<CatalogueSynchronizationReport> lastRun() {
        return store.lastRun();
    }

    private CatalogueSynchronizationReport report(
            UUID id,
            CatalogueSynchronizationRequest request,
            Instant started,
            SynchronizationOutcome outcome,
            String code,
            Counts c) {
        return new CatalogueSynchronizationReport(
                id,
                request.from(),
                request.to(),
                started,
                clock.instant(),
                outcome,
                code,
                new Counters(
                        c.inspected,
                        c.created,
                        c.updated,
                        c.unchanged,
                        c.createdReleases,
                        c.updatedReleases,
                        c.unchangedReleases,
                        c.deferred,
                        c.failed,
                        c.statistics.requests(),
                        c.statistics.retries(),
                        c.statistics.latencyMillis()));
    }

    private static final class Counts {
        long inspected;
        long created;
        long updated;
        long unchanged;
        long createdReleases;
        long updatedReleases;
        long unchangedReleases;
        long deferred;
        long failed;
        ProviderCallStatistics statistics = ProviderCallStatistics.none();
    }
}
