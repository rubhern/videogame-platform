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
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Failure;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.GameResult;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Run;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Stage;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import com.videogameplatform.catalogue.domain.CatalogueSlug;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronizes the complete requested date interval with bounded, in-call provider paging.
 *
 * <p>Progress events report where the run is and why a Game failed; they never change counters,
 * outcomes or the durable report.
 */
public final class CatalogueSynchronizationService implements SynchronizeCatalogueUseCase {
    static final String WORK_NOT_RETURNED = "WORK_NOT_RETURNED";
    static final String TITLE_MISSING = "TITLE_MISSING";
    static final String RELEASE_LIMIT_EXCEEDED = "RELEASE_LIMIT_EXCEEDED";
    static final String DUPLICATE_RELEASE_REFERENCE = "DUPLICATE_RELEASE_REFERENCE";
    static final String RECORD_REJECTED = "RECORD_REJECTED";
    static final String UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE";

    private static final Set<ProviderMappingFailure> BLOCKING_MAPPING_FAILURES =
            Set.of(
                    ProviderMappingFailure.RELEASE_DATE_INVALID,
                    ProviderMappingFailure.RECORD_UNREADABLE,
                    ProviderMappingFailure.DUPLICATE_RELEASE_TUPLE);

    private final CatalogueSynchronizationStore store;
    private final CatalogueProviderPort provider;
    private final Clock clock;
    private final SynchronizationPolicy policy;
    private final CoverSelectionPolicy covers;
    private final SynchronizationProgress progress;

    public CatalogueSynchronizationService(
            CatalogueSynchronizationStore store,
            CatalogueProviderPort provider,
            Clock clock,
            SynchronizationPolicy policy,
            CoverSelectionPolicy covers,
            SynchronizationProgress progress) {
        this.store = store;
        this.provider = provider;
        this.clock = clock;
        this.policy = policy;
        this.covers = covers;
        this.progress = progress;
    }

    @Override
    public CatalogueSynchronizationReport synchronize(CatalogueSynchronizationRequest request) {
        Instant started = clock.instant();
        Counts counts = new Counts();
        if (!provider.isConfigured()) {
            return skipped(request, started, "SYNCHRONIZATION_DISABLED", counts);
        }
        Optional<UUID> acquired =
                store.beginRun(provider.providerName(), request, started, policy.abandonRunAfter());
        if (acquired.isEmpty()) {
            return skipped(request, started, "SYNCHRONIZATION_ALREADY_RUNNING", counts);
        }
        UUID runId = acquired.orElseThrow();
        Run run = progress.started(runId, request, started, policy.providerPageSize());
        long afterGameId = 0;
        int pageNumber = 0;
        try {
            while (true) {
                pageNumber++;
                run.pageRequested(pageNumber, counters(counts));
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
                    run.runFailed(
                            Failure.of(Stage.PROVIDER_PAGE, failure.code().name()),
                            counters(counts));
                    break;
                }
                run.pageFetched(pageNumber, page.gameIds().size(), counters(counts));
                int position = 0;
                for (String providerId : page.gameIds()) {
                    position++;
                    synchronizeGame(
                            runId,
                            new GameProgress(run, pageNumber, position),
                            providerId,
                            started,
                            counts);
                }
                store.heartbeat(runId);
                run.pageCompleted(pageNumber, counters(counts));
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
            run.finished(result);
            return result;
        } catch (RuntimeException failure) {
            counts.failed++;
            run.runFailed(runFailure(failure), counters(counts));
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
            run.finished(result);
            if (!(failure instanceof ProviderRequestException
                    || failure instanceof SynchronizationWriteException)) {
                throw failure;
            }
            return result;
        }
    }

    private void synchronizeGame(
            UUID runId, GameProgress game, String providerId, Instant started, Counts counts) {
        try {
            ProviderWorkBatch workBatch = provider.fetchWorks(List.of(providerId));
            counts.statistics = counts.statistics.plus(workBatch.statistics());
            if (workBatch.works().size() != 1
                    || !providerId.equals(workBatch.works().getFirst().providerId())) {
                fail(
                        game,
                        Failure.of(Stage.PROVIDER_GAME, WORK_NOT_RETURNED),
                        Optional.empty(),
                        counts);
                return;
            }
            reconcile(runId, game, workBatch.works().getFirst(), started, counts);
        } catch (ProviderRequestException failure) {
            counts.statistics = counts.statistics.plus(failure.statistics());
            fail(
                    game,
                    Failure.of(Stage.PROVIDER_GAME, failure.code().name()),
                    Optional.empty(),
                    counts);
        } catch (SynchronizationWriteException failure) {
            // Loading the published Game failed; the store names it when it already knew it.
            fail(
                    game,
                    Failure.of(Stage.PERSISTENCE, failure.reason().name()),
                    failure.game(),
                    counts);
        } catch (IllegalArgumentException _) {
            fail(game, Failure.of(Stage.RECONCILIATION, RECORD_REJECTED), Optional.empty(), counts);
        }
    }

    private void fail(
            GameProgress game,
            Failure failure,
            Optional<SynchronizedGameIdentity> identity,
            Counts counts) {
        counts.failed++;
        game.run().gameFailed(game.page(), game.position(), failure, identity, counters(counts));
    }

    private void succeed(GameProgress game, GameResult result, Counts counts) {
        game.run().gameSucceeded(game.page(), game.position(), result, counters(counts));
    }

    private static Failure runFailure(RuntimeException failure) {
        if (failure instanceof SynchronizationWriteException write) {
            return Failure.of(Stage.PERSISTENCE, write.reason().name());
        }
        if (failure instanceof ProviderRequestException providerFailure) {
            return Failure.of(Stage.PROVIDER_PAGE, providerFailure.code().name());
        }
        return new Failure(
                Stage.RUN, UNEXPECTED_FAILURE, Optional.of(failure.getClass().getName()));
    }

    private static Optional<String> validationFailure(ProviderWork work, int maxReleases) {
        if (work.title() == null || work.title().isBlank()) {
            return Optional.of(TITLE_MISSING);
        }
        if (work.releases().size() > maxReleases) {
            return Optional.of(RELEASE_LIMIT_EXCEEDED);
        }
        return work.mappingFailures().stream()
                .filter(BLOCKING_MAPPING_FAILURES::contains)
                .findFirst()
                .map(Enum::name);
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
            GameProgress game,
            ProviderWork work,
            Instant synchronizedAt,
            Counts counts) {
        Optional<GameState> existing =
                store.loadGame(work.providerId(), policy.maxReleasesPerGame());
        Optional<SynchronizedGameIdentity> published =
                existing.map(
                        state -> SynchronizedGameIdentity.published(state.gameId(), state.slug()));
        if (existing.isEmpty() && !GameImportPolicy.evaluate(work).accepted()) {
            counts.deferred++;
            succeed(game, GameResult.DEFERRED, counts);
            return;
        }
        Optional<String> invalid = validationFailure(work, policy.maxReleasesPerGame());
        if (invalid.isPresent()) {
            // A new Game has no identity yet: validation precedes assignment.
            fail(game, Failure.of(Stage.VALIDATION, invalid.orElseThrow()), published, counts);
            return;
        }
        final SynchronizedGameIdentity identity;
        try {
            identity = published.orElseGet(() -> assignIdentity(work));
        } catch (IllegalArgumentException _) {
            // The title yields no navigable slug, so no identity could be assigned.
            fail(game, Failure.of(Stage.RECONCILIATION, RECORD_REJECTED), published, counts);
            return;
        }
        try {
            publish(runId, game, work, existing, identity, synchronizedAt, counts);
        } catch (DuplicateReleaseReference _) {
            fail(
                    game,
                    Failure.of(Stage.RECONCILIATION, DUPLICATE_RELEASE_REFERENCE),
                    Optional.of(identity),
                    counts);
        } catch (SynchronizationWriteException failure) {
            fail(
                    game,
                    Failure.of(Stage.PERSISTENCE, failure.reason().name()),
                    Optional.of(identity),
                    counts);
        } catch (IllegalArgumentException _) {
            fail(
                    game,
                    Failure.of(Stage.RECONCILIATION, RECORD_REJECTED),
                    Optional.of(identity),
                    counts);
        }
    }

    /** A new Game's identity; it becomes published only if its write commits. */
    private static SynchronizedGameIdentity assignIdentity(ProviderWork work) {
        UUID gameId = UUID.randomUUID();
        String slug =
                CatalogueSlug.fromTitle(work.title()).distinguishedBy(gameId.toString()).value();
        return SynchronizedGameIdentity.unpublished(gameId, slug);
    }

    private void publish(
            UUID runId,
            GameProgress game,
            ProviderWork work,
            Optional<GameState> existing,
            SynchronizedGameIdentity identity,
            Instant synchronizedAt,
            Counts counts) {
        UUID gameId = identity.gameId();
        String slug = identity.slug();
        CoverSelection cover =
                existing.map(
                                published ->
                                        covers.forReconciliation(work.title(), work.cover())
                                                .orElse(published.cover()))
                        .orElseGet(() -> covers.forImport(work.title(), work.cover()));
        List<ReleaseWrite> releases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ProviderRelease providerRelease : work.releases()) {
            if (!seen.add(providerRelease.providerId())) {
                throw new DuplicateReleaseReference();
            }
            PublishedRelease previous =
                    existing.map(GameState::releases)
                            .map(values -> values.get(providerRelease.providerId()))
                            .orElse(null);
            Optional<PlannedRelease> release =
                    ReleaseReconciliationPolicy.reconcile(
                            providerRelease,
                            previous,
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
        GameResult gameResult;
        if (result.createdGame()) {
            counts.created++;
            gameResult = GameResult.CREATED;
        } else if (result.updatedGame()) {
            counts.updated++;
            gameResult = GameResult.UPDATED;
        } else {
            counts.unchanged++;
            gameResult = GameResult.UNCHANGED;
        }
        counts.createdReleases += result.createdReleases();
        counts.updatedReleases += result.updatedReleases();
        counts.unchangedReleases += result.unchangedReleases();
        succeed(game, gameResult, counts);
    }

    @Override
    public Optional<CatalogueSynchronizationReport> lastRun() {
        return store.lastRun();
    }

    private CatalogueSynchronizationReport skipped(
            CatalogueSynchronizationRequest request, Instant started, String code, Counts counts) {
        CatalogueSynchronizationReport result =
                report(null, request, started, SynchronizationOutcome.SKIPPED, code, counts);
        progress.skipped(result);
        return result;
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
                counters(c));
    }

    private static Counters counters(Counts c) {
        return new Counters(
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
                c.statistics.latencyMillis());
    }

    /** Where one Game sits in the run, for progress events only. */
    private record GameProgress(Run run, int page, int position) {}

    /** Two provider releases claimed one external reference; the Game is isolated. */
    private static final class DuplicateReleaseReference extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private DuplicateReleaseReference() {
            super("A release needs a unique provider reference");
        }
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
